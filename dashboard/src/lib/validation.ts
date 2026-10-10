import { localInputToUtcIso } from './time'
import type { CreateActivityInput } from '../api/types'

export const DEFAULT_RADIUS_M = 100
export const MIN_RADIUS_M = 0
export const MAX_RADIUS_M = 5000
export const MIN_CHECKPOINT_MINUTES = 1
export const MAX_CHECKPOINT_MINUTES = 60

export interface ActivityFormValues {
  name: string
  venueName: string
  startsAtLocal: string
  endsAtLocal: string
  latitude: string
  longitude: string
  radius: string
  studentIds: readonly string[]
}

export type ActivityFormErrors = Partial<Record<
  'name' | 'venueName' | 'startsAt' | 'endsAt' | 'latitude' | 'longitude' | 'radius' | 'students', string
>>

export type ValidationResult<T> =
  | { ok: true; value: T }
  | { ok: false; errors: ActivityFormErrors }

function parseNumber(raw: string): number | null {
  if (raw.trim().length === 0) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

export function validateActivityForm(v: ActivityFormValues): ValidationResult<CreateActivityInput> {
  const errors: ActivityFormErrors = {}
  if (v.name.trim().length === 0) errors.name = 'Enter an activity name.'
  if (v.venueName.trim().length === 0) errors.venueName = 'Enter the venue name.'

  const startsAt = localInputToUtcIso(v.startsAtLocal)
  const endsAt = localInputToUtcIso(v.endsAtLocal)
  if (!startsAt) errors.startsAt = 'Enter a start date and time.'
  if (!endsAt) errors.endsAt = 'Enter an end date and time.'
  if (startsAt && endsAt && Date.parse(endsAt) <= Date.parse(startsAt)) {
    errors.endsAt = 'The end must be after the start.'
  }

  const lat = parseNumber(v.latitude)
  const lng = parseNumber(v.longitude)
  if (lat === null || lat < -90 || lat > 90) errors.latitude = 'Latitude must be a number from -90 to 90.'
  if (lng === null || lng < -180 || lng > 180) errors.longitude = 'Longitude must be a number from -180 to 180.'

  const radius = parseNumber(v.radius)
  if (radius === null || !Number.isInteger(radius) || radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) {
    errors.radius = `Radius must be a whole number of metres from ${MIN_RADIUS_M} to ${MAX_RADIUS_M}.`
  }
  if (v.studentIds.length === 0) errors.students = 'Assign at least one student.'

  if (Object.keys(errors).length > 0 || !startsAt || !endsAt || lat === null || lng === null || radius === null) {
    return { ok: false, errors }
  }
  return {
    ok: true,
    value: {
      name: v.name.trim(), venue_name: v.venueName.trim(), venue_lat: lat, venue_lng: lng,
      radius_m: radius, starts_at: startsAt, ends_at: endsAt, student_ids: [...v.studentIds],
    },
  }
}

/** Shared check for "a reason/note is required" fields. Returns an error message or null. */
export function validateRequiredText(value: string, what: string): string | null {
  return value.trim().length === 0 ? `Enter ${what}.` : null
}

export function validateCheckpointMinutes(raw: string): string | null {
  const n = parseNumber(raw)
  if (n === null || !Number.isInteger(n) || n < MIN_CHECKPOINT_MINUTES || n > MAX_CHECKPOINT_MINUTES) {
    return `Enter whole minutes from ${MIN_CHECKPOINT_MINUTES} to ${MAX_CHECKPOINT_MINUTES}.`
  }
  return null
}
