import { useCallback, useEffect, useRef, useState } from 'react'

export interface PollingState<T> {
  data: T | null
  /** True only until the first result (success or failure) arrives. */
  isLoading: boolean
  /** Error from the most recent attempt; data from the last good attempt is kept. */
  error: unknown
  lastUpdated: Date | null
  refresh: () => Promise<void>
}

/**
 * Calls `fetcher` immediately and then every `intervalMs` while the tab is visible.
 * Never starts a request while one is in flight, pauses when the tab is hidden,
 * refreshes on becoming visible again, and stops on unmount.
 */
export function usePolling<T>(fetcher: () => Promise<T>, intervalMs: number): PollingState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const inFlight = useRef(false)
  const rerunRequested = useRef(false)
  const alive = useRef(true)
  const fetcherRef = useRef(fetcher)

  useEffect(() => {
    fetcherRef.current = fetcher
  }, [fetcher])

  const run = useCallback(async (): Promise<void> => {
    if (inFlight.current) {
      // Never overlap. Remember the request so data fetched before a change is followed by a fresh fetch.
      rerunRequested.current = true
      return
    }
    inFlight.current = true
    try {
      do {
        rerunRequested.current = false
        try {
          const result = await fetcherRef.current()
          if (alive.current) {
            setData(result)
            setError(null)
            setLastUpdated(new Date())
          }
        } catch (err) {
          if (alive.current) setError(err)
        }
        if (alive.current) setIsLoading(false)
      } while (rerunRequested.current && alive.current)
    } finally {
      inFlight.current = false
    }
  }, [])

  useEffect(() => {
    alive.current = true
    const isVisible = (): boolean => document.visibilityState !== 'hidden'
    let timer: ReturnType<typeof setInterval> | null = null

    const start = (): void => {
      if (timer !== null) return
      timer = setInterval(() => { if (!inFlight.current) void run() }, intervalMs)
    }
    const stop = (): void => {
      if (timer === null) return
      clearInterval(timer)
      timer = null
    }
    const onVisibility = (): void => {
      if (isVisible()) {
        void run()
        start()
      } else {
        stop()
      }
    }

    void run()
    if (isVisible()) start()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      alive.current = false
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [run, intervalMs])

  return { data, isLoading, error, lastUpdated, refresh: run }
}
