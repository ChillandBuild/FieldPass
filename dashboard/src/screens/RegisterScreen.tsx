import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, messageForError } from '../api'
import { BoundarySummary } from '../components/BoundarySummary'
import { CheckpointControl } from '../components/CheckpointControl'
import { Empty, Loading, Notice } from '../components/Notice'
import { RegisterRow } from '../components/RegisterRow'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { usePolling } from '../hooks/usePolling'
import { saveBlob } from '../lib/download'
import { formatDateTime, formatTimeWithSeconds } from '../lib/time'

export const REGISTER_POLL_MS = 5000

export function RegisterScreen() {
  const { activityId = '' } = useParams()
  const [showBoundary, setShowBoundary] = useState(false)
  const { data, isLoading, error, lastUpdated, refresh } = usePolling(() => api.getRegister(activityId), REGISTER_POLL_MS)
  const csv = useAsyncAction(async () => {
    const file = await api.exportCsv(activityId)
    saveBlob(file.blob, file.filename)
  })

  if (isLoading && !data) return <Loading what="register" />
  if (!data) {
    return (
      <div className="stack">
        <Notice kind="error">{messageForError(error)}</Notice>
        <Link to="/activities">Back to activities</Link>
      </div>
    )
  }

  const { activity, rows } = data
  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <p><Link to="/activities">All activities</Link></p>
          <h1>{activity.name}</h1>
          <p className="muted">
            {activity.venue_name} - {formatDateTime(activity.starts_at)} to {formatDateTime(activity.ends_at)}
          </p>
        </div>
        <div className="btn-row">
          <button type="button" className="btn btn-small" onClick={() => { void csv.run() }} disabled={csv.isBusy}>
            {csv.isBusy ? 'Preparing...' : 'Download CSV'}
          </button>
          <button type="button" className="btn btn-small" aria-expanded={showBoundary} onClick={() => setShowBoundary((v) => !v)}>
            Venue boundary
          </button>
          <Link className="btn btn-small" to="/about">What this system does and does not prove</Link>
        </div>
      </div>
      <p className="muted" role="status">
        Last updated {formatTimeWithSeconds(lastUpdated?.toISOString())}. Refreshes every 5 seconds while this tab is open. Each time shown is a
        point of observation, not proof of presence in between.
      </p>
      {error ? <Notice kind="warn">Could not refresh just now: {messageForError(error)} Showing the last data received.</Notice> : null}
      {csv.error ? <Notice kind="error">{csv.error}</Notice> : null}
      {showBoundary ? <BoundarySummary activity={activity} /> : null}
      <CheckpointControl activity={activity} onOpened={() => { void refresh() }} />
      {rows.length === 0 ? <Empty>No students are on this roster.</Empty> : (
        <section aria-label="Attendance register">
          <ul className="list-plain register">
            <li className="reg-head" aria-hidden="true">
              <span>Student and status</span><span>Arrival</span><span>Last recorded checkpoint</span><span>Departure</span><span>Faculty decision</span>
            </li>
            {rows.map((r) => <RegisterRow key={r.student.id} record={r} onChanged={() => { void refresh() }} />)}
          </ul>
        </section>
      )}
    </div>
  )
}
