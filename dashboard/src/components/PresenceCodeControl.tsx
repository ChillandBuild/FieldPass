import { useState } from 'react'
import type { Activity } from '../api'
import { PresenceCodeOverlay } from './PresenceCodeOverlay'

/** The register's "Show presence code" card. The overlay (and its fetching) exists only while open. */
export function PresenceCodeControl({ activity }: { activity: Pick<Activity, 'id' | 'name'> }) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <section className="card presence-control" aria-label="Presence code">
      <h2>Presence code</h2>
      <p className="muted">
        Show a code that changes every few seconds. A student who scans it during check-in adds one more piece of evidence.
      </p>
      <button type="button" className="btn btn-primary" aria-haspopup="dialog" onClick={() => setIsOpen(true)}>
        Show presence code
      </button>
      {isOpen ? (
        <PresenceCodeOverlay activityId={activity.id} activityName={activity.name} onClose={() => setIsOpen(false)} />
      ) : null}
    </section>
  )
}
