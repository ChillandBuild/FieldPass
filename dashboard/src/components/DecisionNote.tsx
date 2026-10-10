import type { AttendanceRecord, Decision } from '../api'
import { formatDateTime } from '../lib/time'

function verbFor(d: Decision): string {
  return d.decision === 'confirmed' ? 'Confirmed' : 'Not confirmed'
}

/**
 * Faculty decision with reason, who and when; flags decisions superseded by newer automated evidence.
 * Earlier decisions stay visible (decisions are appended, never overwritten).
 * The server sends `decision: null` once newer evidence arrives, so the newest history entry stands in.
 */
export function DecisionNote({ record }: { record: AttendanceRecord }) {
  const d = record.decision ?? record.decision_history[0] ?? null
  if (!d) return <p className="muted">No faculty decision yet.</p>
  const earlier = record.decision_history.filter((h) => h.id !== d.id)
  return (
    <div className={d.is_current ? 'decision' : 'decision decision-outdated'}>
      {d.is_current ? null : (
        <p className="marker marker-warn">Outdated decision, new evidence arrived. Decide again.</p>
      )}
      <p>
        <strong>{verbFor(d)}</strong> by {d.actor_name}, {formatDateTime(d.decided_at)}
      </p>
      <p className="decision-reason">Reason: {d.reason}</p>
      {earlier.length > 0 ? (
        <details className="earlier-decisions">
          <summary>Earlier decisions ({earlier.length})</summary>
          <ul className="list-plain">
            {earlier.map((h) => (
              <li key={h.id}>
                <strong>{verbFor(h)}</strong> by {h.actor_name}, {formatDateTime(h.decided_at)}
                <div className="decision-reason">Reason: {h.reason}</div>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  )
}
