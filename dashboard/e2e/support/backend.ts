import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

export const BACKEND_PORT = Number(process.env.E2E_BACKEND_PORT ?? 8000)
export const DASHBOARD_PORT = Number(process.env.E2E_DASHBOARD_PORT ?? 5173)
export const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`
export const DASHBOARD_ORIGIN = `http://127.0.0.1:${DASHBOARD_PORT}`

const BACKEND_DIR = path.resolve(import.meta.dirname, '../../../backend')
const PYTHON = path.join(BACKEND_DIR, '.venv/bin/python')
const STATE_FILE = path.join(tmpdir(), 'fieldpass-e2e-state.json')

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

async function waitForHealth(timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BACKEND_URL}/api/v1/health`)
      if (res.ok) return
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error('Backend did not become healthy in time')
}

/**
 * Starts the real backend (real OpenCV face engine) on a throwaway database and photo folder,
 * seeds the demo accounts, and records what to clean up in a state file.
 */
export async function startBackend(): Promise<void> {
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
  }
  const seed = spawnSync(PYTHON, ['-m', 'app.seed'], { cwd: BACKEND_DIR, env, encoding: 'utf-8' })
  if (seed.status !== 0) {
    rmSync(tempDir, { recursive: true, force: true })
    throw new Error(`Seeding failed: ${seed.stderr}`)
  }
  const server: ChildProcess = spawn(
    PYTHON,
    ['-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', String(BACKEND_PORT)],
    { cwd: BACKEND_DIR, env, stdio: 'ignore', detached: true },
  )
  server.unref()
  const state: State = { pid: server.pid ?? 0, tempDir, password, facultyPassword }
  writeFileSync(STATE_FILE, JSON.stringify(state), { mode: 0o600 })
  try {
    await waitForHealth(60_000)
  } catch (err) {
    stopBackend()
    throw err
  }
}

/** Stops the server and deletes the throwaway database, photos and state file. */
export function stopBackend(): void {
  if (!existsSync(STATE_FILE)) return
  const state = JSON.parse(readFileSync(STATE_FILE, 'utf-8')) as State
  try {
    if (state.pid) process.kill(-state.pid, 'SIGTERM')
  } catch {
    // already gone
  }
  rmSync(state.tempDir, { recursive: true, force: true })
  rmSync(STATE_FILE, { force: true })
}
