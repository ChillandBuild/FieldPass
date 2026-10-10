import { ApiError } from './errors'
import { getToken } from './config'
import { FACULTY, STUDENTS, emptyRecord, makeDecision } from './fixtureData'
import { recomputeRecord, seedRegisterRows } from './fixtureRegisters'
import { seedActivities, seedEnrollments } from './fixtureData'
import type {
  Activity, Api, AttendanceRecord, CheckpointWindow, Enrollment, Observation, User,
} from './types'

const MIN = 60_000
const LATENCY_MS = 120
const iso = (ms: number): string => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z')
const wait = (): Promise<void> => new Promise((r) => setTimeout(r, LATENCY_MS))

// The fixture store is private to this module. Public methods return fresh copies.
interface Store {
  activities: Activity[]
  rows: Record<string, AttendanceRecord[]>
  enrollments: Enrollment[]
  counter: number
}

function createStore(): Store {
  const now = Date.now()
  const activities = seedActivities(now)
  const rows: Record<string, AttendanceRecord[]> = {}
  for (const a of activities) rows[a.id] = seedRegisterRows(a, now)
  return { activities, rows, enrollments: seedEnrollments(now), counter: 100 }
}

let store = createStore()
let signedIn: User | null = null

export function resetFixtures(): void {
  store = createStore()
  signedIn = null
}

const copy = <T,>(v: T): T => structuredClone(v)
const nextId = (prefix: string): string => `${prefix}_${++store.counter}`

function requireFaculty(): void {
  if (!signedIn) throw new ApiError('not_authenticated', 'Not signed in', 401)
  if (signedIn.role !== 'faculty') throw new ApiError('forbidden', 'Faculty only', 403)
}

function findActivity(id: string): Activity {
  const found = store.activities.find((a) => a.id === id)
  if (!found) throw new ApiError('not_found', 'No such activity', 404)
  return found
}

function findEnrollment(id: string): Enrollment {
  const found = store.enrollments.find((e) => e.id === id)
  if (!found) throw new ApiError('not_found', 'No such enrollment', 404)
  return found
}

function withOpenCheckpoint(a: Activity): Activity {
  const open = a.checkpoint_windows.find((w) => Date.parse(w.closes_at) > Date.now() && Date.parse(w.opens_at) <= Date.now())
  return { ...a, open_checkpoint: open ?? null }
}

function replaceEnrollment(updated: Enrollment): Enrollment {
  store = { ...store, enrollments: store.enrollments.map((e) => (e.id === updated.id ? updated : e)) }
  return copy(updated)
}

function photoBlob(name: string): Blob {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="400" viewBox="0 0 320 400">
<rect width="320" height="400" fill="#d9dee3"/><circle cx="160" cy="150" r="64" fill="#9aa5b1"/>
<path d="M40 400c0-90 55-140 120-140s120 50 120 140z" fill="#9aa5b1"/>
<text x="160" y="380" text-anchor="middle" font-family="sans-serif" font-size="16" fill="#27313b">Sample photo: ${name}</text></svg>`
  return new Blob([svg], { type: 'image/svg+xml' })
}

function csvFor(a: Activity, rows: AttendanceRecord[]): string {
  const head = 'student,username,status,check_in,checkpoints,check_out,missing_checkout'
  const lines = rows.map((r) =>
    [r.student.full_name, r.student.username, r.status ?? '', r.check_in?.received_at ?? '', r.checkpoints.length,
      r.check_out?.received_at ?? '', r.missing_checkout].join(','))
  return [`# ${a.name}`, head, ...lines].join('\n') + '\n'
}

export const fixtureApi: Api = {
  health: async () => { await wait(); return { status: 'ok', server_time: iso(Date.now()) } },
  login: async (username, password) => {
    await wait()
    const user = [FACULTY, ...STUDENTS].find((u) => u.username === username)
    if (!user || password.length === 0) throw new ApiError('invalid_credentials', 'Wrong username or password', 401)
    signedIn = user
    return { access_token: 'fixture-token', token_type: 'bearer', expires_at: iso(Date.now() + 8 * 60 * MIN), user: copy(user) }
  },
  me: async () => {
    await wait()
    // After a page reload the module state is gone; the stored fixture token restores the faculty session.
    if (!signedIn && getToken() === 'fixture-token') signedIn = FACULTY
    if (!signedIn) throw new ApiError('not_authenticated', 'Not signed in', 401)
    return copy(signedIn)
  },
  listEnrollments: async (status) => {
    await wait(); requireFaculty()
    return copy(store.enrollments.filter((e) => !status || e.status === status))
  },
  enrollmentPhoto: async (id) => { await wait(); requireFaculty(); return photoBlob(findEnrollment(id).student.full_name) },
  approveEnrollment: async (id) => {
    await wait(); requireFaculty()
    const e = findEnrollment(id)
    if (e.status !== 'pending') throw new ApiError('already_decided', 'Already decided', 409)
    return replaceEnrollment({ ...e, status: 'approved', decided_at: iso(Date.now()), decided_by: FACULTY.full_name })
  },
  rejectEnrollment: async (id, reason) => {
    await wait(); requireFaculty()
    const e = findEnrollment(id)
    if (e.status !== 'pending') throw new ApiError('already_decided', 'Already decided', 409)
    if (reason.trim().length === 0) throw new ApiError('validation_error', 'Reason required', 422)
    return replaceEnrollment({ ...e, status: 'rejected', decided_at: iso(Date.now()), decided_by: FACULTY.full_name, decision_reason: reason })
  },
  listStudents: async () => { await wait(); requireFaculty(); return copy(STUDENTS) },
  listActivities: async () => { await wait(); requireFaculty(); return copy(store.activities.map(withOpenCheckpoint)) },
  getActivity: async (id) => { await wait(); requireFaculty(); return copy(withOpenCheckpoint(findActivity(id))) },
  createActivity: async (input) => {
    await wait(); requireFaculty()
    const roster = STUDENTS.filter((s) => input.student_ids.includes(s.id))
    const activity: Activity = {
      id: nextId('act'), name: input.name, venue_name: input.venue_name, venue_lat: input.venue_lat,
      venue_lng: input.venue_lng, radius_m: input.radius_m, starts_at: input.starts_at, ends_at: input.ends_at,
      created_by: FACULTY.full_name, roster_count: roster.length, checkpoint_windows: [], open_checkpoint: null,
    }
    store = { ...store, activities: [activity, ...store.activities], rows: { ...store.rows, [activity.id]: roster.map((s) => emptyRecord(activity, s)) } }
    return copy(activity)
  },
  openCheckpoint: async (activityId, minutes) => {
    await wait(); requireFaculty()
    const a = findActivity(activityId)
    if (withOpenCheckpoint(a).open_checkpoint) throw new ApiError('invalid_action_order', 'A window is already open', 409)
    const now = Date.now()
    const w: CheckpointWindow = { id: nextId('cp'), activity_id: a.id, opens_at: iso(now), closes_at: iso(now + minutes * MIN), opened_by: FACULTY.full_name }
    store = { ...store, activities: store.activities.map((x) => (x.id === a.id ? { ...x, checkpoint_windows: [...x.checkpoint_windows, w] } : x)) }
    return copy(w)
  },
  getRegister: async (activityId) => {
    await wait(); requireFaculty()
    const a = withOpenCheckpoint(findActivity(activityId))
    return copy({ activity: a, server_time: iso(Date.now()), rows: store.rows[activityId] ?? [] })
  },
  decide: async (activityId, studentId, input) => {
    await wait(); requireFaculty()
    findActivity(activityId)
    if (input.reason.trim().length === 0) throw new ApiError('validation_error', 'Reason required', 422)
    const rows = store.rows[activityId] ?? []
    const row = rows.find((r) => r.student.id === studentId)
    if (!row) throw new ApiError('not_assigned', 'Not on roster', 403)
    const d = makeDecision(nextId('dec'), input.decision, input.reason, Date.now(), true)
    const history = [...row.decision_history.map((h) => ({ ...h, is_current: false })), d]
    const updated = recomputeRecord({ ...row, decision: d, decision_history: history })
    store = { ...store, rows: { ...store.rows, [activityId]: rows.map((r) => (r.student.id === studentId ? updated : r)) } }
    return copy(updated)
  },
  addObservation: async (activityId, input) => {
    await wait(); requireFaculty()
    findActivity(activityId)
    if (input.note.trim().length === 0) throw new ApiError('validation_error', 'Note required', 422)
    const rows = store.rows[activityId] ?? []
    const row = rows.find((r) => r.student.id === input.student_id)
    if (!row) throw new ApiError('not_assigned', 'Not on roster', 403)
    const obs: Observation = { id: nextId('obs'), activity_id: activityId, student_id: input.student_id, observation_type: input.observation_type, note: input.note, actor_name: FACULTY.full_name, observed_at: iso(Date.now()) }
    const updated = { ...row, observations: [...row.observations, obs] }
    store = { ...store, rows: { ...store.rows, [activityId]: rows.map((r) => (r.student.id === row.student.id ? updated : r)) } }
    return copy(obs)
  },
  exportCsv: async (activityId) => {
    await wait(); requireFaculty()
    const a = findActivity(activityId)
    return { blob: new Blob([csvFor(a, store.rows[activityId] ?? [])], { type: 'text/csv' }), filename: `register-${activityId}.csv` }
  },
}
