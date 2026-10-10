import { act, renderHook } from '@testing-library/react'
import { usePolling } from '../src/hooks/usePolling'

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

/** Flushes pending promise callbacks without moving the fake clock. */
const flush = async (): Promise<void> => { await act(async () => { await Promise.resolve() }) }
const tick = async (ms: number): Promise<void> => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }

describe('usePolling', () => {
  beforeEach(() => { vi.useFakeTimers(); setVisibility('visible') })
  afterEach(() => { vi.useRealTimers() })

  it('fetches immediately, then every interval', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok')
    const { result } = renderHook(() => usePolling(fetcher, 5000))
    await flush()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(result.current.data).toBe('ok')
    expect(result.current.lastUpdated).toBeInstanceOf(Date)
    await tick(5000)
    expect(fetcher).toHaveBeenCalledTimes(2)
    await tick(5000)
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('never overlaps requests: skips ticks while one is in flight', async () => {
    let resolveFirst: (v: string) => void = () => undefined
    const fetcher = vi.fn()
      .mockImplementationOnce(() => new Promise<string>((r) => { resolveFirst = r }))
      .mockResolvedValue('later')
    renderHook(() => usePolling(fetcher, 5000))
    await tick(20_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
    await act(async () => { resolveFirst('first') })
    await tick(5000)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('stops polling on unmount', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok')
    const { unmount } = renderHook(() => usePolling(fetcher, 5000))
    await flush()
    unmount()
    await tick(30_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('pauses while the tab is hidden and refreshes when it becomes visible', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok')
    renderHook(() => usePolling(fetcher, 5000))
    await flush()
    expect(fetcher).toHaveBeenCalledTimes(1)
    act(() => setVisibility('hidden'))
    await tick(30_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
    act(() => setVisibility('visible'))
    await flush()
    expect(fetcher).toHaveBeenCalledTimes(2)
    await tick(5000)
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('keeps the last good data and exposes the error when a poll fails', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce('good').mockRejectedValueOnce(new Error('down')).mockResolvedValue('back')
    const { result } = renderHook(() => usePolling(fetcher, 5000))
    await flush()
    expect(result.current.data).toBe('good')
    await tick(5000)
    expect(result.current.data).toBe('good')
    expect(result.current.error).toBeInstanceOf(Error)
    await tick(5000)
    expect(result.current.data).toBe('back')
    expect(result.current.error).toBeNull()
  })

  it('does not update state after unmount when a request resolves late', async () => {
    let resolveIt: (v: string) => void = () => undefined
    const fetcher = vi.fn(() => new Promise<string>((r) => { resolveIt = r }))
    const { result, unmount } = renderHook(() => usePolling(fetcher, 5000))
    unmount()
    await act(async () => { resolveIt('late') })
    expect(result.current.data).toBeNull()
  })
})

describe('usePolling refresh()', () => {
  beforeEach(() => { vi.useFakeTimers(); setVisibility('visible') })
  afterEach(() => { vi.useRealTimers() })

  it('queues exactly one follow-up fetch when refreshed during a request, and never overlaps', async () => {
    const resolvers: ((v: string) => void)[] = []
    let active = 0
    let maxActive = 0
    const fetcher = vi.fn(() => {
      active += 1
      maxActive = Math.max(maxActive, active)
      return new Promise<string>((r) => { resolvers.push((v) => { active -= 1; r(v) }) })
    })
    const { result } = renderHook(() => usePolling(fetcher, 5000))
    expect(fetcher).toHaveBeenCalledTimes(1)
    void result.current.refresh()
    void result.current.refresh()
    expect(fetcher).toHaveBeenCalledTimes(1)
    await act(async () => { resolvers[0]?.('stale') })
    expect(fetcher).toHaveBeenCalledTimes(2)
    await act(async () => { resolvers[1]?.('fresh') })
    expect(result.current.data).toBe('fresh')
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(maxActive).toBe(1)
  })
})
