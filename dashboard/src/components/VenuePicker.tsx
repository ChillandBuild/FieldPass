import { useEffect, useRef, useState } from 'react'
import { Field } from './Field'
import { UseLocationButton } from './UseLocationButton'
import { VenueMap } from './VenueMap'
import { searchVenues, type VenuePoint, type VenueResult } from '../lib/venueSearch'
import type { VenueRegion } from '../api/types'
import '../styles/venue.css'

interface Props {
  radius: number
  onRadiusChange?: (radius: number) => void
  error?: string
  radiusError?: string
  onChange: (point: VenuePoint | null) => void
  onRegionsChange?: (regions: VenueRegion[] | null) => void
  onName: (name: string) => void
}
interface Draft { id: number; point: VenuePoint | null; radius: number; address: string }

export function VenuePicker({ radius: initialRadius, onRadiusChange, error, radiusError, onChange, onRegionsChange, onName }: Props) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<VenueResult[]>([])
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [regions, setRegions] = useState<Draft[]>([{ id: 1, point: null, radius: initialRadius, address: '' }])
  const [activeId, setActiveId] = useState(1)
  const nextId = useRef(2)
  const active = regions.find((region) => region.id === activeId) ?? regions[0]!
  const { point, radius, address } = active
  const [focusPoint, setFocusPoint] = useState<VenuePoint | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])

  function invalidate(): void { setConfirmed(false); onChange(null); onRegionsChange?.(null) }
  function choose(next: VenuePoint, label?: string, id = active.id): void {
    setRegions((current) => current.map((region) => region.id === id ? { ...region, point: next, address: label ?? (region.address || 'Area placed on the map') } : region))
    invalidate()
  }
  function resize(value: number): void {
    setRegions((current) => current.map((region) => region.id === active.id ? { ...region, radius: value } : region))
    onRadiusChange?.(value)
    invalidate()
  }
  function add(): void {
    const id = nextId.current++
    setRegions((current) => [...current, { id, point: null, radius: 25, address: '' }])
    setActiveId(id)
    setResults([])
    setMessage('Click or double-tap the map to place the new location, or search for it.')
    invalidate()
  }
  function remove(): void {
    const remaining = regions.filter((region) => region.id !== active.id)
    setRegions(remaining)
    setActiveId(remaining[0]!.id)
    invalidate()
  }
  async function search(): Promise<void> {
    if (busy) return
    if (query.trim().length < 3) { setMessage('Enter a venue or address with at least 3 characters.'); return }
    const request = new AbortController()
    controller.current = request
    const timeout = window.setTimeout(() => request.abort(), 12_000)
    setBusy(true); setMessage(''); setResults([])
    try {
      const matches = await searchVenues(query, request.signal)
      if (request.signal.aborted) return
      setResults(matches)
      setMessage(matches.length ? 'Choose a result, then position its circle on the map.' : 'No venues found. Add the city or place the circle directly on the map.')
    } catch { setMessage('Could not search for venues. Place the circle directly on the map or try again.') }
    finally { window.clearTimeout(timeout); setBusy(false) }
  }
  const allPlaced = regions.every((region) => region.point !== null)
  function confirm(): void {
    if (!allPlaced) return
    const selected = regions.map((region) => ({ venue_lat: region.point!.lat, venue_lng: region.point!.lng, radius_m: region.radius }))
    setConfirmed(true)
    onChange(regions[0]!.point)
    onRegionsChange?.(selected)
    try { localStorage.setItem('fieldpass.venueMapCenter', JSON.stringify(point)) } catch { /* optional storage */ }
  }
  return <fieldset className="venue-picker">
    <legend>Venue location</legend>
    <p className="muted">Place a circle for each allowed location. Attendance can count inside any of these areas.</p>
    <div className="btn-row venue-locations" aria-label="Venue locations">
      {regions.map((region, index) => <button type="button" className="btn btn-small" key={region.id} aria-pressed={active.id === region.id}
        onClick={() => setActiveId(region.id)}>Location {index + 1}{region.point ? '' : ' · Place on map'}</button>)}
      <button type="button" className="btn btn-small" onClick={add}>+ Add location</button>
    </div>
    <div className="venue-search">
      <Field label="Search venue or address" hint="Search is optional. Include the city for more accurate results.">
        {(p) => <input id={p.id} value={query} placeholder="e.g. Amrita College, Academic Block 1, city" aria-describedby={p.describedBy}
          disabled={busy} onChange={(event) => { setQuery(event.target.value); setResults([]); setMessage('') }}
          onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); void search() } }} />}
      </Field>
      <button type="button" className="btn" disabled={busy} onClick={() => void search()}>{busy ? 'Searching…' : 'Search venue'}</button>
    </div>
    {message ? <p role="status" className="field-hint">{message}</p> : null}
    {results.length ? <ul className="venue-results" aria-label="Venue search results">
      {results.map((result, index) => <li key={index}><button type="button" className="venue-result"
        onClick={() => { choose(result, result.address); setFocusPoint(result); onName(result.name); setResults([]); setMessage('') }}>
        <strong>{result.name}</strong><span>{result.address}</span>
      </button></li>)}
    </ul> : null}
    <VenueMap point={point} focusPoint={focusPoint} radius={radius} onMove={(next) => choose(next)}
      otherRegions={regions.filter((region) => region.id !== active.id && region.point).map((region) => ({ id: region.id, point: region.point!, radius: region.radius }))}
      onRegionMove={(id, next) => choose(next, undefined, id)} onRegionSelect={setActiveId} />
    <p className="field-hint">Drag a circle or its centre pin. Click or double-tap to place the selected location. Your zoom stays unchanged.</p>
    <div className="venue-size-panel">
      <strong>Location {regions.indexOf(active) + 1}{address ? ` · ${address}` : ' · Click the map to place it'}</strong>
      <Field label="Drag to resize attendance area" error={radiusError}>
        {(p) => <input id={p.id} className="venue-size-slider" type="range" min={0} max={300} step={1} value={radius}
          aria-valuetext={`${radius} metre radius`} aria-describedby={p.describedBy} aria-invalid={p.invalid} onChange={(event) => resize(Number(event.target.value))} />}
      </Field>
      <div className="venue-size-labels"><span>Smaller area</span><span>{radius} m radius</span><span>Larger area</span></div>
      {regions.length > 1 ? <button type="button" className="btn btn-small" onClick={remove}>Remove selected location</button> : null}
    </div>
    <UseLocationButton onLocation={(lat, lng) => { const next = { lat, lng }; choose(next, 'This device’s location'); setFocusPoint(next) }} />
    <div className="venue-confirm">
      <div aria-live="polite"><strong>{confirmed ? 'Venue location confirmed' : 'Check the venue locations'}</strong>
        <p className="muted">{allPlaced ? `${regions.length} attendance ${regions.length === 1 ? 'area' : 'areas'} selected.` : 'Place every added location on the map before confirming.'}</p>
      </div>
      <button type="button" className="btn btn-primary" disabled={!allPlaced || confirmed} onClick={confirm}>
        {confirmed ? 'Location confirmed' : 'Confirm venue location'}
      </button>
    </div>
    {error && !confirmed ? <p role="alert" className="field-error">{error}</p> : null}
    <p className="field-hint">Search by Photon · Map data © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap contributors</a></p>
  </fieldset>
}
