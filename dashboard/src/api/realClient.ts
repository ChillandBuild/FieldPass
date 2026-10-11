import { ApiError, type ApiErrorCode } from './errors'
import { getBaseUrl, getToken } from './config'
import type {
  Activity,
  Api,
  AttendanceRecord,
  CheckpointWindow,
  CreateActivityInput,
  DecisionInput,
  DownloadedFile,
  Enrollment,
  EnrollmentFilter,
  HealthResponse,
  LoginResponse,
  Observation,
  ObservationInput,
  PresenceCode,
  Register,
  Student,
  User,
} from './types'

const KNOWN_CODES: readonly string[] = [
  'invalid_credentials', 'not_authenticated', 'forbidden', 'not_assigned', 'not_found',
  'enrollment_not_approved', 'outside_activity_window', 'invalid_action_order',
  'checkpoint_not_open', 'already_decided', 'attempt_expired', 'image_too_large',
  'validation_error', 'unusable_capture', 'multiple_faces', 'location_stale',
  'device_key_required',
]

interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  form?: FormData
  auth?: boolean
}

function toApiError(status: number, payload: unknown): ApiError {
  const err = (payload as { error?: { code?: unknown; message?: unknown } } | null)?.error
  const code = typeof err?.code === 'string' && KNOWN_CODES.includes(err.code)
    ? (err.code as ApiErrorCode)
    : 'unknown_error'
  const message = typeof err?.message === 'string' ? err.message : `HTTP ${status}`
  return new ApiError(code, message, status)
}

async function send(path: string, opts: RequestOptions = {}): Promise<Response> {
  const headers: Record<string, string> = {}
  if (opts.body !== undefined && !opts.form) headers['Content-Type'] = 'application/json'
  const token = opts.auth === false ? null : getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let res: Response
  try {
    res = await fetch(`${getBaseUrl()}/api/v1${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.form ?? (opts.body === undefined ? undefined : JSON.stringify(opts.body)),
    })
  } catch {
    throw new ApiError('network_error', 'Network error')
  }
  if (!res.ok) {
    const payload: unknown = await res.json().catch(() => null)
    throw toApiError(res.status, payload)
  }
  return res
}

async function json<T>(path: string, opts?: RequestOptions): Promise<T> {
  const res = await send(path, opts)
  try {
    return (await res.json()) as T
  } catch {
    throw new ApiError('unknown_error', 'Unreadable response', res.status)
  }
}

function filenameFrom(res: Response, fallback: string): string {
  const header = res.headers.get('Content-Disposition') ?? ''
  const match = /filename="?([^";]+)"?/i.exec(header)
  return match?.[1] ?? fallback
}

export const realApi: Api = {
  health: () => json<HealthResponse>('/health', { auth: false }),
  login: (username, password) =>
    json<LoginResponse>('/auth/login', { method: 'POST', body: { username, password }, auth: false }),
  me: () => json<User>('/auth/me'),
  listEnrollments: async (status?: EnrollmentFilter) => {
    const q = status ? `?status=${encodeURIComponent(status)}` : ''
    return (await json<{ items: Enrollment[] }>(`/enrollments${q}`)).items
  },
  enrollmentPhoto: async (id) => (await send(`/enrollments/${encodeURIComponent(id)}/photo`)).blob(),
  approveEnrollment: (id) =>
    json<Enrollment>(`/enrollments/${encodeURIComponent(id)}/approve`, {
      method: 'POST',
      body: { identity_confirmed: true },
    }),
  rejectEnrollment: (id, reason) =>
    json<Enrollment>(`/enrollments/${encodeURIComponent(id)}/reject`, { method: 'POST', body: { reason } }),
  uploadEnrollment: (studentId, photo) => {
    const form = new FormData()
    form.append('photo', photo)
    return json<Enrollment>(`/students/${encodeURIComponent(studentId)}/enrollment`, { method: 'POST', form })
  },
  listStudents: async () => (await json<{ items: Student[] }>('/students')).items,
  resetDevice: (id) => json<Student>(`/students/${encodeURIComponent(id)}/device/reset`, { method: 'POST' }),
  listActivities: async () => (await json<{ items: Activity[] }>('/activities')).items,
  getActivity: (id) => json<Activity>(`/activities/${encodeURIComponent(id)}`),
  createActivity: (input: CreateActivityInput) => json<Activity>('/activities', { method: 'POST', body: input }),
  openCheckpoint: (activityId, durationMinutes) =>
    json<CheckpointWindow>(`/activities/${encodeURIComponent(activityId)}/checkpoints`, {
      method: 'POST',
      body: { duration_minutes: durationMinutes },
    }),
  getRegister: (activityId) => json<Register>(`/activities/${encodeURIComponent(activityId)}/register`),
  getPresenceCode: (activityId) => json<PresenceCode>(`/activities/${encodeURIComponent(activityId)}/presence-code`),
  decide: (activityId, studentId, input: DecisionInput) =>
    json<AttendanceRecord>(
      `/activities/${encodeURIComponent(activityId)}/students/${encodeURIComponent(studentId)}/decision`,
      { method: 'POST', body: input },
    ),
  addObservation: (activityId, input: ObservationInput) =>
    json<Observation>(`/activities/${encodeURIComponent(activityId)}/observations`, {
      method: 'POST',
      body: input,
    }),
  exportCsv: async (activityId): Promise<DownloadedFile> => {
    const res = await send(`/activities/${encodeURIComponent(activityId)}/export.csv`)
    return { blob: await res.blob(), filename: filenameFrom(res, `register-${activityId}.csv`) }
  },
}
