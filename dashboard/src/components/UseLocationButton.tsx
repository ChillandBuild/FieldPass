import { useState } from 'react'

interface Props {
  onLocation: (lat: number, lng: number) => void
}

/** Fills coordinates from this device's location. Fails gracefully; coordinates can always be typed. */
export function UseLocationButton({ onLocation }: Props) {
  const [message, setMessage] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator

  function request(): void {
    if (!supported) {
      setMessage('This browser cannot provide a location. Type the coordinates instead.')
      return
    }
    setIsBusy(true)
    setMessage(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setIsBusy(false)
        onLocation(Number(pos.coords.latitude.toFixed(6)), Number(pos.coords.longitude.toFixed(6)))
        setMessage(`Filled from this device (accuracy about ${Math.round(pos.coords.accuracy)} m). Check it is the venue.`)
      },
      (err) => {
        setIsBusy(false)
        setMessage(err.code === err.PERMISSION_DENIED
          ? 'Location permission was denied. Type the coordinates instead.'
          : 'Could not get this device\'s location. Type the coordinates instead.')
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    )
  }

  return (
    <div className="field field-inline">
      <button type="button" className="btn" onClick={request} disabled={isBusy}>
        {isBusy ? 'Getting location...' : 'Use this device\'s location'}
      </button>
      {message ? <span className="field-hint" role="status">{message}</span> : null}
    </div>
  )
}
