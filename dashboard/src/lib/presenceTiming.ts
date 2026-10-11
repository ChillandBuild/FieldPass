import type { PresenceCode } from '../api'

/** Wait this long past expiry so the server has already rolled over to the next code. */
export const REFRESH_GRACE_MS = 300
/** Floor for the wait: an expired or unreadable code must not turn into a request loop. */
export const MIN_DELAY_MS = 1000
/** Ceiling for the wait, so a bad timestamp cannot freeze the display for long. */
export const MAX_DELAY_MS = 60_000
/** Wait before trying again after a temporary failure such as a network drop. */
export const RETRY_DELAY_MS = 5000
/** Shorter wait while a code is still on screen: it has seconds left, so a slow retry would lose it. */
export const HELD_RETRY_DELAY_MS = 2000

/**
 * How long the code still has to live, measured on the SERVER's clock
 * (expires_at minus server_time), so a wrong browser clock changes nothing.
 * Returns 0 for an expired code or unreadable timestamps.
 */
export function remainingMs(code: Pick<PresenceCode, 'expires_at' | 'server_time'>): number {
  const left = Date.parse(code.expires_at) - Date.parse(code.server_time)
  return Number.isFinite(left) && left > 0 ? left : 0
}

/** When to ask for the next code, counted from the moment this one arrived. */
export function nextRefreshDelayMs(code: Pick<PresenceCode, 'expires_at' | 'server_time'>): number {
  return Math.min(MAX_DELAY_MS, Math.max(MIN_DELAY_MS, remainingMs(code) + REFRESH_GRACE_MS))
}
