import { useState } from 'react'

interface Props {
  onLocation: (lat: number, lng: number) => void
}

/** Optional shortcut when faculty are physically at the venue. */
export function UseLocationButton({ onLocation }: Props) {
  const [message, setMessage] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator

  function request(): void {
    if (!supported) {
      setMessage('This browser cannot provide a location. Search for the venue instead.')
      return
    }
    setIsBusy(true)
    setMessage(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setIsBusy(false)
        onLocation(Number(pos.coords.latitude.toFixed(6)), Number(pos.coords.longitude.toFixed(6)))
        setMessage(`Device location accuracy is about ${Math.round(pos.coords.accuracy)} m. Position the circle and confirm the venue location.`)
      },
      (err) => {
        setIsBusy(false)
        setMessage(err.code === err.PERMISSION_DENIED
          ? 'Location permission was denied. Search for the venue instead.'
          : 'Could not get this device\'s location. Search for the venue instead.')
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
