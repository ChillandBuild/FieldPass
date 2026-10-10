import {
  validateActivityForm, validateCheckpointMinutes, validateRequiredText, type ActivityFormValues,
} from '../src/lib/validation'

const valid: ActivityFormValues = {
  name: ' Site Visit ', venueName: 'Plant Gate 2', startsAtLocal: '2026-10-10T15:00', endsAtLocal: '2026-10-10T19:00',
  latitude: '12.9716', longitude: '77.5946', radius: '100', studentIds: ['u_s1', 'u_s2'],
}

describe('activity form validation', () => {
  it('accepts valid input and builds the contract payload with UTC times', () => {
    const r = validateActivityForm(valid)
    expect(r.ok).toBe(true)
    if (r.ok) {
      expect(r.value).toEqual({
        name: 'Site Visit', venue_name: 'Plant Gate 2', venue_lat: 12.9716, venue_lng: 77.5946, radius_m: 100,
        starts_at: '2026-10-10T09:30:00Z', ends_at: '2026-10-10T13:30:00Z', student_ids: ['u_s1', 'u_s2'],
      })
    }
  })
  it('reports every missing field', () => {
    const r = validateActivityForm({ ...valid, name: '', venueName: ' ', latitude: '', longitude: '', studentIds: [] })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(Object.keys(r.errors).sort()).toEqual(['latitude', 'longitude', 'name', 'students', 'venueName'])
  })
  it('rejects out-of-range coordinates', () => {
    const r = validateActivityForm({ ...valid, latitude: '91', longitude: '-181' })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors.latitude && r.errors.longitude).toBeTruthy()
  })
  it.each(['9', '5001', '10.5', 'abc', ''])('rejects radius %s', (radius) => {
    const r = validateActivityForm({ ...valid, radius })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors.radius).toBeTruthy()
  })
  it.each(['10', '5000'])('accepts boundary radius %s', (radius) => {
    expect(validateActivityForm({ ...valid, radius }).ok).toBe(true)
  })
  it('rejects an end that is not after the start', () => {
    const r = validateActivityForm({ ...valid, endsAtLocal: valid.startsAtLocal })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors.endsAt).toMatch(/after the start/)
  })
  it('rejects invalid dates', () => {
    const r = validateActivityForm({ ...valid, startsAtLocal: '' })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors.startsAt).toBeTruthy()
  })
})

describe('required text (decision reason, rejection reason, observation note)', () => {
  it('fails for empty and whitespace-only text', () => {
    expect(validateRequiredText('', 'a reason')).toBe('Enter a reason.')
    expect(validateRequiredText('   \n', 'a reason')).not.toBeNull()
  })
  it('passes for real text', () => expect(validateRequiredText('Seen on site', 'a reason')).toBeNull())
})

describe('checkpoint minutes', () => {
  it.each(['1', '10', '60'])('accepts %s', (v) => expect(validateCheckpointMinutes(v)).toBeNull())
  it.each(['0', '61', '2.5', '', 'x'])('rejects %s', (v) => expect(validateCheckpointMinutes(v)).not.toBeNull())
})
