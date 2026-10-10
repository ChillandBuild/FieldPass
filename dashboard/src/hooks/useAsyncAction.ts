import { useCallback, useEffect, useRef, useState } from 'react'
import { messageForError } from '../api'

/** Runs one async action at a time; exposes busy state and a friendly error message. */
export function useAsyncAction<A extends unknown[], R>(action: (...args: A) => Promise<R>) {
  const [isBusy, setIsBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(true)
  const busy = useRef(false)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const run = useCallback(async (...args: A): Promise<R | undefined> => {
    if (busy.current) return undefined
    busy.current = true
    setIsBusy(true)
    setError(null)
    try {
      return await action(...args)
    } catch (err) {
      if (alive.current) setError(messageForError(err))
      return undefined
    } finally {
      busy.current = false
      if (alive.current) setIsBusy(false)
    }
  }, [action])

  const clearError = useCallback(() => setError(null), [])
  return { run, isBusy, error, clearError }
}
