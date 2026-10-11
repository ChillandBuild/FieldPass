import type {
  Activity,
  Action,
  AttendanceEvent,
  AttendanceRecord,
  Decision,
  Enrollment,
  Observation,
  ReviewReason,
  User,
} from './types'

export const FACULTY: User = { id: 'u_f1', username: 'faculty1', full_name: 'Dr. Meera Rao', role: 'faculty' }
export const STUDENTS: User[] = [
  { id: 'u_s1', username: 'student1', full_name: 'Asha Verma', role: 'student' },
  { id: 'u_s2', username: 'student2', full_name: 'Rohan Iyer', role: 'student' },
  { id: 'u_s3', username: 'student3', full_name: 'Fatima Khan', role: 'student' },
  { id: 'u_s4', username: 'student4', full_name: 'Kabir Menon', role: 'student' },
]

const MIN = 60_000
const iso = (ms: number): string => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z')

interface EventSpec {
  id: string
  student: string
  activity: string
  action: Action
  at: number
  checkpointId?: string
  reasons?: ReviewReason[]
  location?: 'inside' | 'uncertain' | 'outside'
  face?: 'match' | 'uncertain' | 'no_match'
  presence?: boolean
}

export function makeEvent(spec: EventSpec): AttendanceEvent {
  const reasons = spec.reasons ?? []
  const locationVerdict = spec.location ?? 'inside'
  const distance = locationVerdict === 'inside' ? 40 : locationVerdict === 'uncertain' ? 130 : 420
  return {
    id: spec.id,
    attempt_id: `att_${spec.id}`,
    activity_id: spec.activity,
    student_id: spec.student,
    action: spec.action,
    status: reasons.length > 0 ? 'needs_review' : 'recorded',
    received_at: iso(spec.at),
    checkpoint_id: spec.checkpointId ?? null,
    review_reasons: reasons,
    location: {
      verdict: locationVerdict,
      distance_m: distance,
      accuracy_m: locationVerdict === 'uncertain' ? 60 : 15,
      captured_at: iso(spec.at - 7_000),
    },
    face: { verdict: spec.face ?? 'match' },
    presence_verified: spec.presence ?? false,
  }
}

export function makeDecision(
  id: string,
  decision: Decision['decision'],
  reason: string,
  at: number,
  isCurrent: boolean,
): Decision {
  return { id, decision, reason, actor_name: FACULTY.full_name, decided_at: iso(at), is_current: isCurrent }
}

export function makeObservation(
  id: string,
  activity: string,
  student: string,
  type: Observation['observation_type'],
  note: string,
  at: number,
): Observation {
  return { id, activity_id: activity, student_id: student, observation_type: type, note, actor_name: FACULTY.full_name, observed_at: iso(at) }
}

export function emptyRecord(activity: Activity, student: User): AttendanceRecord {
  return {
    activity_id: activity.id,
    activity_name: activity.name,
    student,
    status: null,
    check_in: null,
    checkpoints: [],
    check_out: null,
    missing_checkout: false,
    complete: false,
    review_reasons: [],
    decision: null,
    decision_history: [],
    observations: [],
    next_actions: [],
  }
}

export function seedActivities(now: number): Activity[] {
  const base = { created_by: FACULTY.full_name, venue_lat: 12.9716, venue_lng: 77.5946 }
  return [
    {
      ...base, id: 'act_1', name: 'Site Visit: Water Treatment Plant', venue_name: 'Plant Gate 2', radius_m: 100,
      starts_at: iso(now - 90 * MIN), ends_at: iso(now + 150 * MIN), roster_count: 4,
      checkpoint_windows: [{ id: 'cp_1', activity_id: 'act_1', opens_at: iso(now - 40 * MIN), closes_at: iso(now - 30 * MIN), opened_by: FACULTY.full_name }],
      open_checkpoint: null,
    },
    {
      ...base, id: 'act_2', name: 'Lab Safety Walkthrough', venue_name: 'Chemistry Block, Room 12', radius_m: 60,
      venue_lat: 12.9352, venue_lng: 77.6245,
      starts_at: iso(now - 24 * 60 * MIN), ends_at: iso(now - 22 * 60 * MIN), roster_count: 3,
      checkpoint_windows: [], open_checkpoint: null,
    },
  ]
}

export function seedEnrollments(now: number): Enrollment[] {
  const pending = (id: string, student: User, ago: number): Enrollment => ({
    id, status: 'pending', student, submitted_at: iso(now - ago * MIN),
    decided_at: null, decided_by: null, decision_reason: null,
  })
  return [
    pending('enr_1', STUDENTS[0]!, 25),
    pending('enr_2', STUDENTS[1]!, 12),
    pending('enr_3', STUDENTS[3]!, 3),
  ]
}
