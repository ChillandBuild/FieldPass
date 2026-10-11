// Mirrors contracts/API.md (v1). Do not add fields that are not in the contract.

export type Role = 'student' | 'faculty'
export type EnrollmentStatus = 'none' | 'pending' | 'approved' | 'rejected'
export type Action = 'check_in' | 'checkpoint' | 'check_out'
export type AttendanceStatus = 'recorded' | 'needs_review' | 'confirmed' | 'not_confirmed'
export type LocationVerdict = 'inside' | 'uncertain' | 'outside'
export type FaceVerdict = 'match' | 'uncertain' | 'no_match'
export type ReviewReason =
  | 'location_uncertain'
  | 'location_outside'
  | 'face_mismatch'
  | 'face_uncertain'
  | 'liveness_failed'
  | 'location_mocked'
  | 'location_repeated'
  | 'device_rooted'
  | 'device_emulator'
export type ObservationType = 'present' | 'absent' | 'left_early' | 'other'
export type DecisionKind = 'confirmed' | 'not_confirmed'

export interface User {
  id: string
  username: string
  full_name: string
  role: Role
}

/** A student as faculty see them: when their phone was bound (null: none, the next login binds one). */
export interface Student extends User {
  device_bound_at: string | null
}

export interface Enrollment {
  id: string | null
  status: EnrollmentStatus
  student: User
  submitted_at: string | null
  decided_at: string | null
  decided_by: string | null
  decision_reason: string | null
}

export interface CheckpointWindow {
  id: string
  activity_id: string
  opens_at: string
  closes_at: string
  opened_by: string
}

export interface Activity {
  venue_regions?: VenueRegion[]
  id: string
  name: string
  venue_name: string
  venue_lat: number
  venue_lng: number
  radius_m: number
  starts_at: string
  ends_at: string
  created_by: string
  roster_count: number
  checkpoint_windows: CheckpointWindow[]
  open_checkpoint: CheckpointWindow | null
}

export interface LocationEvidence {
  verdict: LocationVerdict
  distance_m: number
  accuracy_m: number
  captured_at: string
}

export interface FaceEvidence {
  verdict: FaceVerdict
}

export interface AttendanceEvent {
  id: string
  attempt_id: string
  activity_id: string
  student_id: string
  action: Action
  status: 'recorded' | 'needs_review'
  received_at: string
  checkpoint_id: string | null
  review_reasons: ReviewReason[]
  location: LocationEvidence
  face: FaceEvidence
}

export interface Decision {
  id: string
  decision: DecisionKind
  reason: string
  actor_name: string
  decided_at: string
  is_current: boolean
}

export interface Observation {
  id: string
  activity_id: string
  student_id: string
  observation_type: ObservationType
  note: string
  actor_name: string
  observed_at: string
}

export interface AttendanceRecord {
  activity_id: string
  activity_name: string
  student: User
  status: AttendanceStatus | null
  check_in: AttendanceEvent | null
  checkpoints: AttendanceEvent[]
  check_out: AttendanceEvent | null
  missing_checkout: boolean
  complete: boolean
  review_reasons: ReviewReason[]
  decision: Decision | null
  decision_history: Decision[]
  observations: Observation[]
  next_actions: Action[]
}

export interface Register {
  activity: Activity
  server_time: string
  rows: AttendanceRecord[]
}

export interface LoginResponse {
  access_token: string
  token_type: string
  expires_at: string
  user: User
}

export interface HealthResponse {
  status: string
  server_time: string
}

export interface VenueRegion { venue_lat: number; venue_lng: number; radius_m: number }

export interface CreateActivityInput {
  name: string
  venue_name: string
  venue_lat: number
  venue_lng: number
  radius_m: number
  venue_regions?: VenueRegion[]
  starts_at: string
  ends_at: string
  student_ids: string[]
}

export interface DecisionInput {
  decision: DecisionKind
  reason: string
}

export interface ObservationInput {
  student_id: string
  observation_type: ObservationType
  note: string
}

export type EnrollmentFilter = 'pending' | 'approved' | 'rejected'

export interface DownloadedFile {
  blob: Blob
  filename: string
}

/** The single typed surface implemented by both the real client and the fixture client. */
export interface Api {
  health(): Promise<HealthResponse>
  login(username: string, password: string): Promise<LoginResponse>
  me(): Promise<User>
  listEnrollments(status?: EnrollmentFilter): Promise<Enrollment[]>
  enrollmentPhoto(id: string): Promise<Blob>
  approveEnrollment(id: string): Promise<Enrollment>
  rejectEnrollment(id: string, reason: string): Promise<Enrollment>
  /** Faculty upload a student's enrollment photo. It is pending until a faculty member approves it. */
  uploadEnrollment(studentId: string, photo: File): Promise<Enrollment>
  listStudents(): Promise<Student[]>
  resetDevice(studentId: string): Promise<Student>
  listActivities(): Promise<Activity[]>
  getActivity(id: string): Promise<Activity>
  createActivity(input: CreateActivityInput): Promise<Activity>
  /** Replaces the activity's details and roster. Allowed up to the day before it starts. */
  updateActivity(id: string, input: CreateActivityInput): Promise<Activity>
  /** Only works while the activity has no attendance records. */
  deleteActivity(id: string): Promise<void>
  openCheckpoint(activityId: string, durationMinutes: number): Promise<CheckpointWindow>
  getRegister(activityId: string): Promise<Register>
  decide(activityId: string, studentId: string, input: DecisionInput): Promise<AttendanceRecord>
  addObservation(activityId: string, input: ObservationInput): Promise<Observation>
  exportCsv(activityId: string): Promise<DownloadedFile>
}
