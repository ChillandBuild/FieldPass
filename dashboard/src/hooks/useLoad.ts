import { useCallback, useEffect, useRef, useState } from 'react'
import { messageForError } from '../api'

export interface LoadState<T> {
  data: T | null
  isLoading: boolean
  error: string | null
  reload: () => void
}

interface Settled<T> {
  tick: number
  data: T | null
  error: string | null
}

/** Loads once on mount and again on reload(). Ignores results that arrive after unmount or a newer reload. */
export function useLoad<T>(loader: () => Promise<T>): LoadState<T> {
  const [tick, setTick] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const loaderRef = useRef(loader)

  useEffect(() => {
    loaderRef.current = loader
  }, [loader])

  useEffect(() => {
    let cancelled = false
    loaderRef.current()
      .then((data) => { if (!cancelled) setSettled({ tick, data, error: null }) })
      .catch((err: unknown) => { if (!cancelled) setSettled({ tick, data: null, error: messageForError(err) }) })
    return () => { cancelled = true }
  }, [tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  const isLoading = settled === null || settled.tick !== tick
  return { data: settled?.data ?? null, isLoading, error: isLoading ? null : settled.error, reload }
}
