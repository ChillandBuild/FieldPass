import { memo, useEffect, useRef } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { QRCodeSVG } from 'qrcode.react'
import { isApiError, messageForError } from '../api'
import { usePresenceCode } from '../hooks/usePresenceCode'

/** A QR must be dark on light with a quiet zone, whatever the page theme is. */
const QR_BACKGROUND = '#ffffff'
const QR_FOREGROUND = '#000000'
/** Modules of empty border around the code. The QR standard asks for at least 4. */
const QR_QUIET_ZONE_MODULES = 4
const QR_PIXEL_SIZE = 480

interface Props {
  activityId: string
  activityName: string
  onClose: () => void
}

/** Only re-renders when the code string changes, not on every countdown tick. */
const PresenceQr = memo(function PresenceQr({ value, stepSeconds }: { value: string; stepSeconds: number }) {
  return (
    <div className="presence-qr" role="img"
      aria-label={`QR code for the current presence code. It changes every ${stepSeconds} seconds. Scan it with the FieldPass app.`}>
      <QRCodeSVG value={value} size={QR_PIXEL_SIZE} level="M" bgColor={QR_BACKGROUND} fgColor={QR_FOREGROUND}
        marginSize={QR_QUIET_ZONE_MODULES} aria-hidden="true" focusable="false" />
    </div>
  )
})

function errorText(err: unknown): string {
  if (isApiError(err) && err.code === 'outside_activity_window') {
    return 'This activity is not running right now, so there is no presence code to show. Open it during its scheduled time.'
  }
  return messageForError(err)
}

function focusableIn(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>('button:not(:disabled), [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'))
}

/** Full-screen presence code for a projector. Fetches only while mounted, so closing it stops all requests. */
export function PresenceCodeOverlay({ activityId, activityName, onClose }: Props) {
  const { code, error, isLoading, msLeft, retry } = usePresenceCode(activityId)
  const panelRef = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)

  useEffect(() => { onCloseRef.current = onClose }, [onClose])

  // Focus moves into the dialog on open and returns to whatever opened it on close.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    panelRef.current?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
      if (opener?.isConnected) opener.focus()
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onCloseRef.current()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  function keepFocusInside(e: ReactKeyboardEvent<HTMLDivElement>): void {
    if (e.key !== 'Tab' || !panelRef.current) return
    const items = focusableIn(panelRef.current)
    const first = items[0]
    const last = items[items.length - 1]
    if (!first || !last) return
    const active = document.activeElement
    if (e.shiftKey && (active === first || active === panelRef.current)) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && active === last) {
      e.preventDefault()
      first.focus()
    }
  }

  const step = code?.step_seconds ?? 10
  const secondsLeft = Math.ceil(msLeft / 1000)
  const percentLeft = code ? Math.min(100, (msLeft / (step * 1000)) * 100) : 0

  return createPortal(
    <div className="presence-overlay">
      <div ref={panelRef} className="presence-panel" role="dialog" aria-modal="true" aria-labelledby="presence-title"
        tabIndex={-1} onKeyDown={keepFocusInside}>
        <div className="presence-head">
          <div>
            <h2 id="presence-title">Presence code</h2>
            <p className="muted presence-activity">{activityName}</p>
          </div>
          <button type="button" className="btn" onClick={onClose}>Close</button>
        </div>
        {error ? (
          <div className="notice notice-error presence-error" role="alert">
            <p>{errorText(error)}</p>
            <button type="button" className="btn btn-small" onClick={retry}>Try again</button>
          </div>
        ) : isLoading || !code ? (
          <p role="status" className="muted presence-loading"><span className="spinner" aria-hidden="true" /> Getting the code...</p>
        ) : (
          <>
            <PresenceQr value={code.code} stepSeconds={step} />
            <p className="presence-instruction">Scan this with the FieldPass app</p>
            <div className="presence-timer" aria-hidden="true">
              <p>
                {secondsLeft > 0
                  ? <>Changes in <strong data-testid="presence-countdown">{secondsLeft}</strong> s</>
                  : <strong data-testid="presence-countdown">Updating...</strong>}
              </p>
              <div className="presence-bar"><span style={{ width: `${percentLeft}%` }} /></div>
            </div>
            <p className="sr-only">The code changes every {step} seconds.</p>
          </>
        )}
      </div>
    </div>,
    document.body,
  )
}
