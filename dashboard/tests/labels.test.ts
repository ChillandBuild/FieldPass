import type { AttendanceStatus, ReviewReason } from '../src/api'
import { observationLabel, reasonLabel, statusLabel } from '../src/lib/labels'

describe('status labels', () => {
  const cases: [AttendanceStatus | null, string][] = [
    ['recorded', 'Recorded'],
    ['needs_review', 'Needs review'],
    ['confirmed', 'Confirmed by faculty'],
    ['not_confirmed', 'Not confirmed by faculty'],
    [null, 'No check-in yet'],
  ]
  it.each(cases)('labels %s', (status, label) => {
    expect(statusLabel(status)).toBe(label)
  })
})

describe('review reason labels', () => {
  const cases: [ReviewReason, string][] = [
    ['location_uncertain', 'Location uncertain'],
    ['location_outside', 'Location outside the boundary'],
    ['face_mismatch', 'Face comparison did not match'],
    ['face_uncertain', 'Face comparison uncertain'],
    ['liveness_failed', 'Head-turn check failed'],
    ['location_mocked', 'Fake GPS was on'],
    ['location_repeated', 'Same exact spot as an earlier check-in'],
    ['device_rooted', 'Rooted phone'],
    ['device_emulator', 'Running on an emulator'],
  ]
  it.each(cases)('labels %s in plain words', (reason, label) => {
    expect(reasonLabel(reason)).toBe(label)
  })
  it('never uses confidence or liveness wording', () => {
    const all = cases.map(([, l]) => l).join(' ').toLowerCase()
    expect(all).not.toMatch(/confidence|liveness|live person/)
  })
})

describe('observation labels', () => {
  it('labels left_early', () => expect(observationLabel('left_early')).toBe('Left early'))
})
