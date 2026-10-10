import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

export const BACKEND_PORT = Number(process.env.E2E_BACKEND_PORT ?? 8000)
export const DASHBOARD_PORT = Number(process.env.E2E_DASHBOARD_PORT ?? 5173)
export const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`
export const DASHBOARD_ORIGIN = `http://127.0.0.1:${DASHBOARD_PORT}`

const BACKEND_DIR = path.resolve(import.meta.dirname, '../../../backend')
const PYTHON = path.join(BACKEND_DIR, process.platform === 'win32' ? '.venv/Scripts/python.exe' : '.venv/bin/python')
const STATE_FILE = path.join(tmpdir(), 'fieldpass-e2e-state.json')
const SHORT_STATE_FILE = path.join(tmpdir(), 'fieldpass-e2e-short-state.json')

/** The second backend (short attempt TTL, zero checkout grace) used by timing.spec.ts. */
export const SHORT_BACKEND_PORT = Number(process.env.E2E_SHORT_BACKEND_PORT ?? 8001)
export const SHORT_BACKEND_URL = `http://127.0.0.1:${SHORT_BACKEND_PORT}`
export const SHORT_ATTEMPT_TTL_SECONDS = 4

interface State {
  pid: number
  tempDir: string
  password: string
  facultyPassword: string
}

/** Reads a secret setting from the environment or backend/.env. Never logged. */
function readSetting(name: string): string {
  const fromEnv = process.env[name]
  if (fromEnv) return fromEnv
  const envFile = path.join(BACKEND_DIR, '.env')
  for (const line of readFileSync(envFile, 'utf-8').split('\n')) {
    const match = new RegExp(`^${name}=(.*)$`).exec(line.trim())
    if (match?.[1]) return match[1].replace(/^["']|["']$/g, '')
  }
  throw new Error(`${name} not found in backend/.env`)
}

/** Password of the demo account: faculty accounts have their own (FACULTY_SEED_PASSWORD), students share SEED_PASSWORD. */
export function passwordFor(username: string): string {
  const state = JSON.parse(readFileSync(STATE_FILE, 'utf-8')) as State
  return username.startsWith('faculty') ? state.facultyPassword : state.password
}

async function waitForHealth(baseUrl: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${baseUrl}/api/v1/health`)
      if (res.ok) return
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error('Backend did not become healthy in time')
}

interface LaunchOptions {
  port: number
  stateFile: string
  /** Extra settings for this server only, on top of backend/.env. */
  settings: Record<string, string>
}

/**
 * Starts the real backend (real OpenCV face engine) on a throwaway database and photo folder,
 * seeds the demo accounts, and records what to clean up in a state file.
 */
async function launchBackend({ port, stateFile, settings }: LaunchOptions): Promise<void> {
  if (!existsSync(PYTHON)) throw new Error(`Backend virtualenv missing: ${PYTHON}`)
  if (!existsSync(path.join(BACKEND_DIR, 'models/face_recognition_sface_2021dec.onnx'))) {
    const dl = spawnSync(PYTHON, ['scripts/download_models.py'], { cwd: BACKEND_DIR, stdio: 'inherit' })
    if (dl.status !== 0) throw new Error('Model download failed')
  }
  const tempDir = mkdtempSync(path.join(tmpdir(), 'fieldpass-e2e-'))
  const password = readSetting('SEED_PASSWORD')
  const facultyPassword = readSetting('FACULTY_SEED_PASSWORD')
  const env = {
    ...process.env,
    DATABASE_PATH: path.join(tempDir, 'e2e.db'),
    PHOTO_DIR: path.join(tempDir, 'photos'),
    CORS_ORIGINS: DASHBOARD_ORIGIN,
    // Never reach a real database or storage bucket from backend/.env: these must be blank so the backend
    // uses the throwaway SQLite file and local photo folder above.
    DATABASE_URL: '',
    SUPABASE_URL: '',
    SUPABASE_SERVICE_KEY: '',
    // There are no turned-head sample photos, so the e2e sends the selfie twice and the turn rule is opened
    // wide. backend/tests/test_liveness.py proves the rule itself.
    LIVENESS_FRONT_MAX_OFFSET: '9',
    LIVENESS_TURN_MIN_OFFSET: '-9',
    ...settings,
  }
  const seed = spawnSync(PYTHON, ['-m', 'app.seed'], { cwd: BACKEND_DIR, env, encoding: 'utf-8' })
  if (seed.status !== 0) {
    rmSync(tempDir, { recursive: true, force: true })
    throw new Error(`Seeding failed: ${seed.stderr}`)
  }
  const server: ChildProcess = spawn(
    PYTHON,
    ['-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', String(port)],
    { cwd: BACKEND_DIR, env, stdio: 'ignore', detached: true },
  )
  server.unref()
  const state: State = { pid: server.pid ?? 0, tempDir, password, facultyPassword }
  writeFileSync(stateFile, JSON.stringify(state), { mode: 0o600 })
  try {
    await waitForHealth(`http://127.0.0.1:${port}`, 60_000)
  } catch (err) {
    haltBackend(stateFile)
    throw err
  }
}

/** Stops the server recorded in `stateFile` and deletes its throwaway database, photos and the state file. */
function haltBackend(stateFile: string): void {
  if (!existsSync(stateFile)) return
  const state = JSON.parse(readFileSync(stateFile, 'utf-8')) as State
  try {
    if (state.pid) process.kill(-state.pid, 'SIGTERM')
  } catch {
    // already gone
  }
  rmSync(state.tempDir, { recursive: true, force: true })
  rmSync(stateFile, { force: true })
}

export function startBackend(): Promise<void> {
  return launchBackend({ port: BACKEND_PORT, stateFile: STATE_FILE, settings: {} })
}

export function stopBackend(): void {
  haltBackend(STATE_FILE)
}

/**
 * Second real backend on its own throwaway data: a few seconds of attempt lifetime and no check-out grace,
 * so attempt expiry and a missing check-out happen after a short real wait. Nothing in the app is faked.
 */
export function startShortBackend(): Promise<void> {
  return launchBackend({
    port: SHORT_BACKEND_PORT,
    stateFile: SHORT_STATE_FILE,
    settings: { ATTEMPT_TTL_SECONDS: String(SHORT_ATTEMPT_TTL_SECONDS), CHECKOUT_GRACE_MINUTES: '0' },
  })
}

export function stopShortBackend(): void {
  haltBackend(SHORT_STATE_FILE)
}
