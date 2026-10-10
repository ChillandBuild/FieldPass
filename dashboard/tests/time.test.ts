import { dateToLocalInput, formatDateTime, formatTime, localInputToUtcIso, localTimeZoneLabel } from '../src/lib/time'

describe('local input to UTC', () => {
  it('converts IST local time to a UTC ISO string with Z and no milliseconds', () => {
    expect(localInputToUtcIso('2026-10-10T15:00')).toBe('2026-10-10T09:30:00Z')
  })
  it('rolls the date back across midnight', () => {
    expect(localInputToUtcIso('2026-10-10T02:00')).toBe('2026-10-09T20:30:00Z')
  })
  it('rejects empty and malformed values', () => {
    expect(localInputToUtcIso('')).toBeNull()
    expect(localInputToUtcIso('2026-10-10')).toBeNull()
    expect(localInputToUtcIso('2026-13-45T99:99')).toBeNull()
  })
  it('round-trips through dateToLocalInput', () => {
    const iso = localInputToUtcIso('2026-10-10T15:00')
    expect(dateToLocalInput(new Date(iso as string))).toBe('2026-10-10T15:00')
  })
})

describe('display formatting', () => {
  it('shows local time with the timezone abbreviation', () => {
    const out = formatTime('2026-10-10T09:30:00Z', 'en-GB')
    expect(out).toContain('15:00')
    expect(out).toMatch(/IST|GMT\+5:30/)
  })
  it('includes the date in formatDateTime', () => {
    expect(formatDateTime('2026-10-10T09:30:00Z', 'en-GB')).toContain('10 Oct')
  })
  it('returns an em dash for null or invalid input', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatDateTime('not a date')).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
  })
  it('reports a timezone label', () => {
    expect(localTimeZoneLabel(new Date('2026-10-10T09:30:00Z'), 'en-GB')).toMatch(/IST|GMT\+5:30/)
  })
})
