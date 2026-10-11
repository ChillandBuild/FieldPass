import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, type KeyObject } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { BACKEND_URL, passwordFor } from './backend'
import { canonicalMessage } from './canonical'

const SAMPLES = path.resolve(import.meta.dirname, '../../../backend/tests/samples')

export type Sample = 'person_a_1' | 'person_a_2' | 'person_b'

export interface ApiResult<T = unknown> {
  status: number
  body: T
  headers: Headers
}

export const VENUE = { lat: 12.9716, lng: 77.5946 }

export interface Attempt {
  id: string
  action: string
  nonce: string
  challenge: 'left' | 'right'
  created_at: string
  expires_at: string
}

export interface SubmitOptions {
  photo: Sample
  lat?: number
  lng?: number
  accuracyM?: number
  /** Report the location as mocked (fake GPS), as the phone would. */
  isMock?: boolean
}

export interface AttendanceEventBody {
  id: string
  status: string
  review_reasons: string[]
  face: { verdict: string }
  location: { verdict: string }
}

function sampleBytes(name: Sample): Buffer {
  return readFileSync(path.join(SAMPLES, `${name}.jpg`))
}

const jpeg = (bytes: Buffer): Blob => new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' })

/**
 * One phone key per student per backend. Kept in a temp file so a restarted Playwright worker sends the same
 * key (a new key would be refused with device_mismatch). Each run's backend is freshly seeded, so a key left
 * over from an earlier run simply binds on first login.
 */
function deviceKeys(backendUrl: string): (username: string) => KeyObject {
  const file = path.join(tmpdir(), `fieldpass-e2e-keys-${new URL(backendUrl).port}.json`)
  let pems: Record<string, string> = existsSync(file) ? JSON.parse(readFileSync(file, 'utf-8')) : {}
  return (username) => {
    if (!pems[username]) {
      const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
      pems = { ...pems, [username]: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString() }
      writeFileSync(file, JSON.stringify(pems))
    }
    return createPrivateKey(pems[username] as string)
  }
}

const publicKeyB64 = (key: KeyObject): string =>
  createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64')

/** The student/faculty API as called from a script, bound to one backend's base URL. */
export function createStudentApi(backendUrl: string) {
  const root = `${backendUrl}/api/v1`
  const keyFor = deviceKeys(backendUrl)
  const owners = new Map<string, string>() // token -> username
  const nonces = new Map<string, string>() // attempt id -> nonce
  let submits = 0

  async function call<T>(method: string, route: string, token: string | null, body?: BodyInit, json?: unknown): Promise<ApiResult<T>> {
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    let payload = body
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(json)
    }
    const res = await fetch(`${root}${route}`, { method, headers, body: payload })
    const text = await res.text()
    let parsed: unknown = text
    try {
      parsed = JSON.parse(text)
    } catch {
      // csv or empty
    }
    return { status: res.status, body: parsed as T, headers: res.headers }
  }

  const api = {
    get: <T>(route: string, token: string) => call<T>('GET', route, token),
    post: <T>(route: string, token: string | null, json: unknown) => call<T>('POST', route, token, undefined, json),
  }

  async function login(username: string): Promise<string> {
    const res = await api.post<{ access_token: string }>('/auth/login', null, {
      username, password: passwordFor(username), device_public_key: publicKeyB64(keyFor(username)),
    })
    if (res.status !== 200) throw new Error(`login ${username} failed: ${res.status}`)
    owners.set(res.body.access_token, username)
    return res.body.access_token
  }

  async function enroll(token: string, photo: Sample): Promise<ApiResult<{ id: string; status: string }>> {
    const form = new FormData()
    form.append('photo', jpeg(sampleBytes(photo)), `${photo}.jpg`)
    return call('POST', '/enrollment', token, form)
  }

  async function approveAsFaculty(facultyToken: string, enrollmentId: string): Promise<void> {
    const res = await api.post(`/enrollments/${enrollmentId}/approve`, facultyToken, { identity_confirmed: true })
    if (res.status !== 200) throw new Error(`approve failed: ${res.status}`)
  }

  async function createAttempt(token: string, activityId: string, action: string): Promise<ApiResult<Attempt>> {
    const res = await api.post<Attempt>(`/activities/${activityId}/attempts`, token, { action })
    if (res.status === 201) nonces.set(res.body.id, res.body.nonce)
    return res
  }

  /**
   * Submits a selfie and location for an attempt, signed like the phone. Safe to call again with the same id.
   * The selfie doubles as the head-turn photo (the e2e backend runs with wide head-turn limits).
   * Each default location is nudged by 0.000001° (~0.1 m) so check-in and check-out are not flagged as repeated.
   */
  async function submitAttempt(token: string, attemptId: string, opts: SubmitOptions): Promise<ApiResult<AttendanceEventBody>> {
    submits += 1
    const selfie = sampleBytes(opts.photo)
    const lat = opts.lat ?? VENUE.lat + submits * 0.000001
    const lng = opts.lng ?? VENUE.lng
    const accuracyM = opts.accuracyM ?? 15
    const capturedEpoch = Math.floor(Date.now() / 1000)
    const isMock = opts.isMock ?? false
    const owner = owners.get(token)
    if (!owner) throw new Error('submitAttempt: this token was not issued by login() in this script, so no phone key can sign for it')
    const nonce = nonces.get(attemptId)
    if (!nonce) throw new Error(`submitAttempt: no nonce known for attempt ${attemptId}; create it with createAttempt() first`)
    const message = canonicalMessage({
      attemptId, nonce, selfie, turn: selfie, lat, lng, accuracyM, capturedEpoch,
      isMock, rooted: false, emulator: false,
    })
    const form = new FormData()
    form.append('selfie', jpeg(selfie), 'selfie.jpg')
    form.append('turn_photo', jpeg(selfie), 'turn.jpg')
    form.append('lat', lat.toFixed(6))
    form.append('lng', lng.toFixed(6))
    form.append('accuracy_m', accuracyM.toFixed(1))
    form.append('location_captured_at', new Date(capturedEpoch * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z'))
    form.append('location_is_mock', String(isMock))
    form.append('device_rooted', 'false')
    form.append('device_emulator', 'false')
    form.append('signature', sign('sha256', message, { key: keyFor(owner), dsaEncoding: 'der' }).toString('base64'))
    return call('POST', `/attempts/${attemptId}/submit`, token, form)
  }

  /** Creates an attempt and submits it in one go; throws if either step is refused. */
  async function checkAction(token: string, activityId: string, action: string, opts: SubmitOptions) {
    const attempt = await createAttempt(token, activityId, action)
    if (attempt.status !== 201) throw new Error(`attempt ${action} refused: ${attempt.status} ${JSON.stringify(attempt.body)}`)
    const event = await submitAttempt(token, attempt.body.id, opts)
    if (event.status !== 200) throw new Error(`submit ${action} refused: ${event.status} ${JSON.stringify(event.body)}`)
    return { attemptId: attempt.body.id, event: event.body }
  }

  return { api, login, enroll, approveAsFaculty, createAttempt, submitAttempt, checkAction }
}

export const { api, login, enroll, approveAsFaculty, createAttempt, submitAttempt, checkAction } = createStudentApi(BACKEND_URL)
