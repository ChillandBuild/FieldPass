export interface VenuePoint { lat: number; lng: number }
export interface VenueResult extends VenuePoint { name: string; address: string }

const cache = new Map<string, VenueResult[]>()
const endpoint = import.meta.env.VITE_VENUE_SEARCH_URL || 'https://photon.komoot.io/api/'

/** Search only on request; repeated searches use cached results. */
export async function searchVenues(query: string, signal: AbortSignal): Promise<VenueResult[]> {
  const key = query.trim()
  const cached = cache.get(key)
  if (cached) return cached
  const url = new URL(endpoint)
  url.searchParams.set('q', key)
  url.searchParams.set('limit', '5')
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error('Venue search is unavailable. Try again or choose a point on the map.')
  const data = await response.json() as { features?: unknown[] }
  if (!Array.isArray(data.features)) throw new Error('Venue search returned an unreadable response. Try again.')
  const results: VenueResult[] = []
  for (const raw of data.features) {
    const feature = raw as { geometry?: { coordinates?: unknown[] }; properties?: Record<string, unknown> } | null
    const [lng, lat] = feature?.geometry?.coordinates ?? []
    if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)
      || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue
    const p = feature?.properties ?? {}
    const text = (key: string) => typeof p[key] === 'string' ? p[key] as string : ''
    const name = text('name') || text('street') || text('city') || 'Venue'
    const address = [...new Set([name, [text('housenumber'), text('street')].filter(Boolean).join(' '),
      text('district'), text('city'), text('state'), text('country')].filter(Boolean))].join(', ')
    results.push({ lat, lng, name, address })
  }
  if (cache.size >= 30) cache.clear()
  cache.set(key, results)
  return results
}
