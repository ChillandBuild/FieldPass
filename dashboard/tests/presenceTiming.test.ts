import { MAX_DELAY_MS, MIN_DELAY_MS, REFRESH_GRACE_MS, nextRefreshDelayMs, remainingMs } from '../src/lib/presenceTiming'

const code = (serverTime: string, expiresAt: string) => ({
  code: 'fp1.1.abc', server_time: serverTime, expires_at: expiresAt, step_seconds: 10,
})

describe('remainingMs', () => {
  it('is expires_at minus server_time, not the browser clock', () => {
    // Year 2001 timestamps: the browser clock is irrelevant to the result.
    expect(remainingMs(code('2001-01-01T00:00:03Z', '2001-01-01T00:00:10Z'))).toBe(7000)
  })
  it('keeps sub-second precision', () => {
    expect(remainingMs(code('2026-01-01T00:00:00.250Z', '2026-01-01T00:00:10.000Z'))).toBe(9750)
  })
  it('is 0 when the code is already expired', () => {
    expect(remainingMs(code('2026-01-01T00:00:12Z', '2026-01-01T00:00:10Z'))).toBe(0)
  })
  it('is 0 for unreadable timestamps', () => {
    expect(remainingMs(code('nope', '2026-01-01T00:00:10Z'))).toBe(0)
    expect(remainingMs(code('2026-01-01T00:00:00Z', ''))).toBe(0)
  })
})

describe('nextRefreshDelayMs', () => {
  it('waits for the remaining life plus a small grace so the next code is already live', () => {
    expect(nextRefreshDelayMs(code('2026-01-01T00:00:03Z', '2026-01-01T00:00:10Z'))).toBe(7000 + REFRESH_GRACE_MS)
  })
  it('never goes below the minimum, so a bad or expired code cannot cause a request loop', () => {
    expect(nextRefreshDelayMs(code('2026-01-01T00:00:12Z', '2026-01-01T00:00:10Z'))).toBe(MIN_DELAY_MS)
    expect(nextRefreshDelayMs(code('bad', 'bad'))).toBe(MIN_DELAY_MS)
  })
  it('never goes above the maximum', () => {
    expect(nextRefreshDelayMs(code('2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z'))).toBe(MAX_DELAY_MS)
  })
})
