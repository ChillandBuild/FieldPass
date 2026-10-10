import type {
  Action, AttendanceStatus, FaceVerdict, LocationVerdict, ObservationType, ReviewReason,
} from '../api/types'

export const STATUS_LABELS: Record<AttendanceStatus, string> = {
  recorded: 'Recorded',
  needs_review: 'Needs review',
  confirmed: 'Confirmed by faculty',
  not_confirmed: 'Not confirmed by faculty',
}

export const NO_STATUS_LABEL = 'No check-in yet'

export function statusLabel(status: AttendanceStatus | null): string {
  return status ? STATUS_LABELS[status] : NO_STATUS_LABEL
}

export const REASON_LABELS: Record<ReviewReason, string> = {
  location_uncertain: 'Location uncertain',
  location_outside: 'Location outside the boundary',
  face_mismatch: 'Face comparison did not match',
  face_uncertain: 'Face comparison uncertain',
  liveness_failed: 'Head-turn check failed',
  location_mocked: 'Fake GPS was on',
  location_repeated: 'Same exact spot as an earlier check-in',
  device_rooted: 'Rooted phone',
  device_emulator: 'Running on an emulator',
}

export function reasonLabel(reason: ReviewReason): string {
  return REASON_LABELS[reason]
}

export const OBSERVATION_LABELS: Record<ObservationType, string> = {
  present: 'Present',
  absent: 'Absent',
  left_early: 'Left early',
  other: 'Other',
}

export const OBSERVATION_TYPES: readonly ObservationType[] = ['present', 'absent', 'left_early', 'other']

export function observationLabel(type: ObservationType): string {
  return OBSERVATION_LABELS[type]
}

export const ACTION_LABELS: Record<Action, string> = {
  check_in: 'Arrival',
  checkpoint: 'Checkpoint',
  check_out: 'Departure',
}

export const LOCATION_LABELS: Record<LocationVerdict, string> = {
  inside: 'Location inside the boundary',
  uncertain: 'Location uncertain',
  outside: 'Location outside the boundary',
}

export const FACE_LABELS: Record<FaceVerdict, string> = {
  match: 'Face comparison matched',
  uncertain: 'Face comparison uncertain',
  no_match: 'Face comparison did not match',
}
