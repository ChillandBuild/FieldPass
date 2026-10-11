import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, messageForError } from '../api'
import { BoundarySummary } from '../components/BoundarySummary'
import { CheckpointControl } from '../components/CheckpointControl'
import { Empty, Loading, Notice } from '../components/Notice'
import { PresenceCodeControl } from '../components/PresenceCodeControl'
import { RegisterRow } from '../components/RegisterRow'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { usePolling } from '../hooks/usePolling'
import { saveBlob } from '../lib/download'
import { formatDateTime, formatTimeWithSeconds } from '../lib/time'

export const REGISTER_POLL_MS = 5000

export function RegisterScreen() {
  const { activityId = '' } = useParams()
  const navigate = useNavigate()
  const [showBoundary, setShowBoundary] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const { data, isLoading, error, lastUpdated, refresh } = usePolling(() => api.getRegister(activityId), REGISTER_POLL_MS)
  const csv = useAsyncAction(async () => {
    const file = await api.exportCsv(activityId)
    saveBlob(file.blob, file.filename)
  })
  const remove = useAsyncAction(async () => {
    await api.deleteActivity(activityId)
    navigate('/activities')
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
      <div className="page-head register-title">
        <div>
          <p><Link to="/activities">All activities</Link></p>
          <h1>{activity.name}</h1>
          <p className="activity-venue">{activity.venue_name}</p>
          <p className="muted activity-schedule">{formatDateTime(activity.starts_at)} to {formatDateTime(activity.ends_at)}</p>
        </div>
        <div className="btn-row">
          <button type="button" className="btn btn-small" onClick={() => { void csv.run() }} disabled={csv.isBusy}>
            {csv.isBusy ? 'Preparing...' : 'Download CSV'}
          </button>
          <button type="button" className="btn btn-small" aria-expanded={showBoundary} onClick={() => setShowBoundary((v) => !v)}>
            Venue boundary
          </button>
          <Link className="btn btn-small" to="/about">What this system does and does not prove</Link>
          <Link className="btn btn-small" to={`/activities/${activity.id}/edit`}>Edit details</Link>
          <button type="button" className="btn btn-small btn-danger" aria-expanded={confirmDelete} onClick={() => setConfirmDelete((v) => !v)}>
            Delete
          </button>
        </div>
      </div>
      {confirmDelete ? (
        <Notice kind="warn">
          Delete {activity.name}? This removes the activity and its roster and cannot be undone. An activity that already has
          attendance records cannot be deleted.
          <div className="btn-row">
            <button type="button" className="btn btn-small btn-danger" disabled={remove.isBusy} onClick={() => { void remove.run() }}>
              {remove.isBusy ? 'Deleting...' : 'Yes, delete'}
            </button>
            <button type="button" className="btn btn-small" disabled={remove.isBusy} onClick={() => setConfirmDelete(false)}>Keep it</button>
          </div>
        </Notice>
      ) : null}
      {remove.error ? <Notice kind="error">{remove.error}</Notice> : null}
      <dl className="register-summary" aria-label="Register summary">
        <div><dt>Roster</dt><dd>{activity.roster_count}<span>students</span></dd></div>
        <div><dt>Checked in</dt><dd>{rows.filter((row) => row.check_in !== null).length}<span>with an arrival recorded</span></dd></div>
        <div><dt>Awaiting review</dt><dd>{rows.filter((row) => row.status === 'needs_review').length}<span>flagged for faculty review</span></dd></div>
      </dl>
      <p className="muted register-update" role="status">
        Last updated {formatTimeWithSeconds(lastUpdated?.toISOString())}. Refreshes every 5 seconds while this tab is open. Each time shown is a
        point of observation, not proof of presence in between.
      </p>
      {error ? <Notice kind="warn">Could not refresh just now: {messageForError(error)} Showing the last data received.</Notice> : null}
      {csv.error ? <Notice kind="error">{csv.error}</Notice> : null}
      {showBoundary ? <BoundarySummary activity={activity} /> : null}
      <div className="register-tools">
        <CheckpointControl activity={activity} onOpened={() => { void refresh() }} />
        <PresenceCodeControl activity={activity} />
      </div>
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
