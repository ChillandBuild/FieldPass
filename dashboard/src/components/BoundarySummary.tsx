import type { Activity } from '../api'

/** Plain statement of the venue boundary: centre coordinates and radius. */
export function BoundarySummary({ activity }: { activity: Activity }) {
  const regions = activity.venue_regions?.length ? activity.venue_regions : [activity]
  return (
    <section className="boundary-box" aria-label="Venue boundary">
      <h2>Venue boundary</h2>
      <p>Attendance can count inside any of the selected circles.</p>
      {regions.map((region, index) => <div key={index}>
        {regions.length > 1 ? <h3>Location {index + 1}</h3> : null}
        <dl>
          <dt>Centre latitude</dt><dd className="mono">{region.venue_lat}</dd>
          <dt>Centre longitude</dt><dd className="mono">{region.venue_lng}</dd>
          <dt>Radius</dt><dd>{region.radius_m} metres</dd>
        </dl>
      </div>)}
    </section>
  )
}
