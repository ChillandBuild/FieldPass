import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import { Circle, MapContainer, Marker, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import type { VenuePoint } from '../lib/venueSearch'

interface Props {
  point: VenuePoint | null; focusPoint?: VenuePoint | null; radius: number; onMove: (point: VenuePoint) => void
  otherRegions?: { id: number; point: VenuePoint; radius: number }[]
  onRegionMove?: (id: number, point: VenuePoint) => void
  onRegionSelect?: (id: number) => void
}
const pinIcon = L.divIcon({ className: 'venue-pin', html: '<span></span>', iconSize: [24, 32], iconAnchor: [12, 32] })

function initialCentre(): L.LatLngTuple {
  try {
    const saved = JSON.parse(localStorage.getItem('fieldpass.venueMapCenter') || 'null') as VenuePoint | null
    if (saved && Number.isFinite(saved.lat) && Number.isFinite(saved.lng) && Math.abs(saved.lat) <= 85 && Math.abs(saved.lng) <= 180) {
      return [saved.lat, saved.lng]
    }
  } catch { /* use the initial city view */ }
  return [12.9716, 77.5946]
}

function DraggableArea({ point, radius, onMove }: Props & { point: VenuePoint }) {
  const map = useMap()
  const circle = useRef<L.Circle | null>(null)
  const onMoveRef = useRef(onMove)
  useEffect(() => { onMoveRef.current = onMove }, [onMove])
  useEffect(() => {
    const element = map.getContainer()
    let drag: { pointerId: number; latOffset: number; lngOffset: number; wasPannable: boolean } | null = null
    const finish = () => {
      if (drag?.wasPannable) map.dragging.enable()
      drag = null
      circle.current?.getElement()?.classList.remove('venue-area-dragging')
    }
    const down = (event: PointerEvent) => {
      const path = circle.current?.getElement()
      if (event.button !== 0 || !circle.current || event.target !== path) return
      event.preventDefault()
      event.stopPropagation()
      const cursor = map.mouseEventToLatLng(event)
      const centre = circle.current.getLatLng()
      drag = { pointerId: event.pointerId, latOffset: centre.lat - cursor.lat, lngOffset: centre.lng - cursor.lng, wasPannable: map.dragging.enabled() }
      map.dragging.disable()
      path.setPointerCapture(event.pointerId)
      path.classList.add('venue-area-dragging')
    }
    const move = (event: PointerEvent) => {
      if (!drag || event.pointerId !== drag.pointerId) return
      event.preventDefault()
      const cursor = map.mouseEventToLatLng(event)
      onMoveRef.current({ lat: Math.max(-85, Math.min(85, cursor.lat + drag.latOffset)),
        lng: ((cursor.lng + drag.lngOffset + 540) % 360) - 180 })
    }
    const up = (event: PointerEvent) => { if (drag?.pointerId === event.pointerId) finish() }
    element.addEventListener('pointerdown', down, true)
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', up)
    return () => {
      finish()
      element.removeEventListener('pointerdown', down, true)
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', up)
    }
  }, [map])
  return <Circle ref={circle} center={[point.lat, point.lng]} radius={radius}
    pathOptions={{ color: '#8427a4', fillOpacity: 0.25, weight: 3, className: 'venue-draggable-area' }}
    eventHandlers={{ click: (event) => L.DomEvent.stopPropagation(event.originalEvent) }} />
}

function MapEvents({ focusPoint, onMove }: Pick<Props, 'focusPoint' | 'onMove'>) {
  const map = useMapEvents({
    click: (event) => onMove({ lat: event.latlng.lat, lng: event.latlng.lng }),
    dblclick: (event) => onMove({ lat: event.latlng.lat, lng: event.latlng.lng }),
  })
  useEffect(() => {
    // Only a search result or device-location request changes the view.
    // Moving the attendance area must preserve the user's zoom and pan.
    if (focusPoint) map.setView([focusPoint.lat, focusPoint.lng], 19)
  }, [map, focusPoint])
  useEffect(() => {
    const observer = new ResizeObserver(() => map.invalidateSize())
    observer.observe(map.getContainer())
    return () => observer.disconnect()
  }, [map])
  return null
}

function ZoomToArea({ point, radius }: Pick<Props, 'point' | 'radius'>) {
  const map = useMap()
  return <button type="button" className="btn btn-small venue-map-focus" disabled={!point} onClick={(event) => {
    event.stopPropagation()
    if (point) map.fitBounds(L.latLng(point.lat, point.lng).toBounds(radius * 2), { padding: [35, 35], maxZoom: 21 })
  }}>Focus on selected area</button>
}

function Tiles() {
  const map = useMap()
  const [failed, setFailed] = useState(false)
  return <>
    <TileLayer url={import.meta.env.VITE_VENUE_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'} maxZoom={22} maxNativeZoom={19}
      attribution={import.meta.env.VITE_VENUE_TILE_ATTRIBUTION || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
      eventHandlers={{ tileerror: () => setFailed(true), load: () => {
        if (map.getContainer().querySelector('.leaflet-tile-loaded')) setFailed(false)
      } }} />
    {failed ? <div className="venue-map-error" role="status">Map tiles could not load. Check your internet connection before confirming the venue.</div> : null}
  </>
}

export function VenueMap({ point, focusPoint, radius, onMove, otherRegions = [], onRegionMove, onRegionSelect }: Props) {
  return <>
    <div role="region" aria-label="Venue map. Drag the circle or its centre pin to position the attendance area.">
      <MapContainer center={point ? [point.lat, point.lng] : initialCentre()} zoom={19} maxZoom={22} doubleClickZoom={false} scrollWheelZoom={false} className="venue-map">
        <Tiles />
        <ZoomToArea point={point} radius={radius} />
        <MapEvents focusPoint={focusPoint} onMove={onMove} />
        {otherRegions.map((region) => <span key={region.id}>
          <DraggableArea point={region.point} radius={region.radius} onMove={(next) => onRegionMove?.(region.id, next)} />
          <Marker position={[region.point.lat, region.point.lng]} icon={pinIcon} draggable title={`Location ${region.id} centre pin`} alt={`Location ${region.id} centre pin`}
            eventHandlers={{ click: () => onRegionSelect?.(region.id), drag: (event) => {
              const moved = (event.target as L.Marker).getLatLng()
              onRegionMove?.(region.id, { lat: moved.lat, lng: moved.lng })
            } }} />
        </span>)}
        {point ? <>
          <DraggableArea point={point} radius={radius} onMove={onMove} />
          <Marker position={[point.lat, point.lng]} icon={pinIcon} draggable title="Venue centre pin" alt="Venue centre pin"
            eventHandlers={{ drag: (event) => {
              const moved = (event.target as L.Marker).getLatLng()
              onMove({ lat: moved.lat, lng: moved.lng })
            } }} />
        </> : null}
      </MapContainer>
    </div>
  </>
}
