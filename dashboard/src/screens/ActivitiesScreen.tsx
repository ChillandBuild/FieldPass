import { Link } from 'react-router-dom'
import { api } from '../api'
import { Empty, Loading, Notice } from '../components/Notice'
import { useLoad } from '../hooks/useLoad'
import { formatDateTime } from '../lib/time'

export function ActivitiesScreen() {
  const { data, isLoading, error, reload } = useLoad(() => api.listActivities())
  return (
    <>
      <div className="page-head">
        <h1>Activities</h1>
        <div className="btn-row">
          <button type="button" className="btn btn-small" onClick={reload} disabled={isLoading}>Refresh</button>
          <Link className="btn btn-primary btn-small" to="/activities/new">New activity</Link>
        </div>
      </div>
      {error ? <Notice kind="error">{error}</Notice> : null}
      {isLoading && !data ? <Loading what="activities" /> : null}
      {data && data.length === 0 ? <Empty>No activities yet. Create the first one.</Empty> : null}
      <ul className="list-plain activity-list">
        {(data ?? []).map((a) => (
          <li key={a.id} className="card activity-row">
            <div>
              <h2>{a.name}</h2>
              <p className="muted">
                {a.venue_name} - {formatDateTime(a.starts_at)} to {formatDateTime(a.ends_at)} - {a.roster_count} students
              </p>
              {a.open_checkpoint ? <p><strong>Checkpoint window open</strong> until {formatDateTime(a.open_checkpoint.closes_at)}</p> : null}
            </div>
            <Link className="btn" to={`/activities/${a.id}`} aria-label={`Open register for ${a.name}`}>Open register</Link>
          </li>
        ))}
      </ul>
    </>
  )
}
