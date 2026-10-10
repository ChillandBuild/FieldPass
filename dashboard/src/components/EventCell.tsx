import type { AttendanceEvent } from '../api'
import { FACE_LABELS, LOCATION_LABELS } from '../lib/labels'
import { formatDateTime } from '../lib/time'

/** One automated observation: server receipt time and the check results in plain words. */
export function EventDetail({ event }: { event: AttendanceEvent }) {
  return (
    <div className="event">
      <div className="event-time">{formatDateTime(event.received_at)}</div>
      <div className="muted event-checks">
        {LOCATION_LABELS[event.location.verdict]}; {FACE_LABELS[event.face.verdict]}
      </div>
    </div>
  )
}

export function NoneRecorded({ text }: { text: string }) {
  return <span className="muted">{text}</span>
}

export function CheckpointsCell({ checkpoints }: { checkpoints: readonly AttendanceEvent[] }) {
  const latest = checkpoints[checkpoints.length - 1]
  if (!latest) return <NoneRecorded text="None recorded" />
  return (
    <div>
      <EventDetail event={latest} />
      {checkpoints.length > 1 ? (
        <details className="earlier">
          <summary>All {checkpoints.length} checkpoints</summary>
          {checkpoints.map((c) => <EventDetail key={c.id} event={c} />)}
        </details>
      ) : null}
    </div>
  )
}
