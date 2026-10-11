import { useCallback, useEffect, useRef, useState } from 'react'
import { api, isApiError } from '../api'
import type { PresenceCode } from '../api'
import { HELD_RETRY_DELAY_MS, RETRY_DELAY_MS, nextRefreshDelayMs, remainingMs } from '../lib/presenceTiming'

const COUNTDOWN_TICK_MS = 250
/** Errors that may clear by themselves. Anything else (not found, not running, no access) needs a person. */
const TEMPORARY_CODES = new Set(['network_error', 'unknown_error'])

export interface PresenceCodeState {
  code: PresenceCode | null
  /** The thrown value from the latest failed request; null while the code is good. */
  error: unknown
  isLoading: boolean
  /** Milliseconds the shown code has left, from the server's clock anchored at arrival. 0 when none. */
  msLeft: number
  /** Ask for a code now (the "Try again" button). Ignored while a request is running. */
  retry: () => void
}

interface Held {
  code: PresenceCode
  /** performance.now() reading at which the code expires. Monotonic: immune to browser clock changes. */
  deadline: number
}

/**
 * Keeps a fresh presence code while `active`. One request at a time, a timer set from the server's
 * expires_at minus server_time, paused while the tab is hidden, stopped on unmount. Unlike usePolling
 * the wait changes every time (it follows the code's life), so it chains timeouts instead of an interval.
 */
export function usePresenceCode(activityId: string, active = true): PresenceCodeState {
  const [held, setHeld] = useState<Held | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [now, setNow] = useState(() => performance.now())
  const loadRef = useRef<() => void>(() => undefined)

  useEffect(() => {
    if (!active) return undefined
    let alive = true
    let inFlight = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let shown: Held | null = null
    const isVisible = (): boolean => document.visibilityState !== 'hidden'
    const clearTimer = (): void => {
      if (timer !== null) clearTimeout(timer)
      timer = null
    }
    const schedule = (ms: number): void => {
      clearTimer()
      timer = setTimeout(load, ms)
    }

    function load(): void {
      clearTimer()
      if (inFlight || !alive || !isVisible()) return
      inFlight = true
      api.getPresenceCode(activityId).then(
        (code) => {
          inFlight = false
          if (!alive) return
          const arrived = performance.now()
          shown = { code, deadline: arrived + remainingMs(code) }
          setHeld(shown)
          setNow(arrived)
          setError(null)
          setIsLoading(false)
          if (isVisible()) schedule(nextRefreshDelayMs(code))
        },
        (err: unknown) => {
          inFlight = false
          if (!alive) return
          const isTemporary = !isApiError(err) || TEMPORARY_CODES.has(err.code)
          // The server accepts a code for one more step after it rotates, so a network blip need not blank
          // the screen students are scanning. Past that the code is useless and the error is shown.
          if (isTemporary && shown && performance.now() < shown.deadline + shown.code.step_seconds * 1000) {
            if (isVisible()) schedule(HELD_RETRY_DELAY_MS)
            return
          }
          shown = null
          setHeld(null)
          setError(err)
          setIsLoading(false)
          if (isVisible() && isTemporary) schedule(RETRY_DELAY_MS)
        },
      )
    }

    const onVisibility = (): void => {
      if (isVisible()) load()
      else clearTimer()
    }
    loadRef.current = load
    load()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      alive = false
      clearTimer()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [activityId, active])

  useEffect(() => {
    if (!held) return undefined
    const id = setInterval(() => setNow(performance.now()), COUNTDOWN_TICK_MS)
    return () => clearInterval(id)
  }, [held])

  const retry = useCallback(() => { loadRef.current() }, [])
  const msLeft = held ? Math.max(0, held.deadline - now) : 0
  return { code: held?.code ?? null, error, isLoading, msLeft, retry }
}
