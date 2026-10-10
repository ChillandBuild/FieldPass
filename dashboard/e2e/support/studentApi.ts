import { readFileSync } from 'node:fs'
import path from 'node:path'
import { BACKEND_URL, passwordFor } from './backend'

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
  created_at: string
  expires_at: string
}

export interface SubmitOptions {
  photo: Sample
  lat?: number
  lng?: number
  accuracyM?: number
}

export interface AttendanceEventBody {
  id: string
  status: string
  review_reasons: string[]
  face: { verdict: string }
  location: { verdict: string }
}

function sample(name: Sample): Blob {
  return new Blob([readFileSync(path.join(SAMPLES, `${name}.jpg`))], { type: 'image/jpeg' })
}

/** The student/faculty API as called from a script, bound to one backend's base URL. */
export function createStudentApi(backendUrl: string) {
  const root = `${backendUrl}/api/v1`

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
    const res = await api.post<{ access_token: string }>('/auth/login', null, { username, password: passwordFor(username) })
    if (res.status !== 200) throw new Error(`login ${username} failed: ${res.status}`)
    return res.body.access_token
  }

  async function enroll(token: string, photo: Sample): Promise<ApiResult<{ id: string; status: string }>> {
    const form = new FormData()
    form.append('photo', sample(photo), `${photo}.jpg`)
    return call('POST', '/enrollment', token, form)
  }

  async function approveAsFaculty(facultyToken: string, enrollmentId: string): Promise<void> {
    const res = await api.post(`/enrollments/${enrollmentId}/approve`, facultyToken, { identity_confirmed: true })
    if (res.status !== 200) throw new Error(`approve failed: ${res.status}`)
  }

  async function createAttempt(token: string, activityId: string, action: string): Promise<ApiResult<Attempt>> {
    return api.post<Attempt>(`/activities/${activityId}/attempts`, token, { action })
  }

  /** Submits a selfie and location for an attempt. Safe to call again with the same attempt id. */
  async function submitAttempt(token: string, attemptId: string, opts: SubmitOptions): Promise<ApiResult<AttendanceEventBody>> {
    const form = new FormData()
    form.append('selfie', sample(opts.photo), 'selfie.jpg')
    form.append('lat', String(opts.lat ?? VENUE.lat))
    form.append('lng', String(opts.lng ?? VENUE.lng))
    form.append('accuracy_m', String(opts.accuracyM ?? 15))
    form.append('location_captured_at', new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'))
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
