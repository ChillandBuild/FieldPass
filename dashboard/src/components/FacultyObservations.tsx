import type { Observation } from '../api'
import { observationLabel } from '../lib/labels'
import { formatDateTime } from '../lib/time'

/** In-person faculty observations, kept visually and textually separate from automated ones. */
export function FacultyObservations({ observations }: { observations: readonly Observation[] }) {
  if (observations.length === 0) return null
  return (
    <div className="faculty-obs">
      <h4>In-person observations by faculty</h4>
      <ul className="list-plain">
        {observations.map((o) => (
          <li key={o.id}>
            <strong>{observationLabel(o.observation_type)}</strong> - {o.note}
            <div className="muted">{o.actor_name}, {formatDateTime(o.observed_at)}</div>
          </li>
        ))}
      </ul>
    </div>
  )
}
