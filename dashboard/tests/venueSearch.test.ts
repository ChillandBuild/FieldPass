import { afterEach, describe, expect, it, vi } from 'vitest'
import { searchVenues } from '../src/lib/venueSearch'
afterEach(() => vi.unstubAllGlobals())
describe('venue search provider', () => {
  it('converts GeoJSON longitude/latitude correctly and caches repeated searches', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ features: [
      { geometry: { coordinates: [77.59, 12.97] }, properties: { name: 'Garden', city: 'Bengaluru' } },
      { geometry: { coordinates: [1000, 500] }, properties: { name: 'Invalid' } },
    ] }) })
    vi.stubGlobal('fetch', fetchMock)
    const signal = new AbortController().signal
    const venues = await searchVenues('Garden Bengaluru test', signal)
    expect(venues).toEqual([{ name: 'Garden', address: 'Garden, Bengaluru', lat: 12.97, lng: 77.59 }])
    expect(await searchVenues('Garden Bengaluru test', signal)).toEqual(venues)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('rejects a service error rather than treating it as an empty search', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    await expect(searchVenues('offline test', new AbortController().signal)).rejects.toThrow('unavailable')
  })
})
