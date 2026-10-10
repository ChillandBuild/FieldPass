import type { Activity } from '../api'

/** Plain statement of the venue boundary: centre coordinates and radius. */
export function BoundarySummary({ activity }: { activity: Activity }) {
  return (
    <section className="boundary-box" aria-label="Venue boundary">
      <h2>Venue boundary</h2>
      <p>Attendance is compared against a circle around this point.</p>
      <dl>
        <dt>Centre latitude</dt><dd className="mono">{activity.venue_lat}</dd>
        <dt>Centre longitude</dt><dd className="mono">{activity.venue_lng}</dd>
        <dt>Radius</dt><dd>{activity.radius_m} metres</dd>
      </dl>
    </section>
  )
}
