import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import type { Activity } from '../api'
import { BoundarySummary } from '../components/BoundarySummary'
import { Field } from '../components/Field'
import { Loading, Notice } from '../components/Notice'
import { StudentPicker } from '../components/StudentPicker'
import { VenuePicker } from '../components/VenuePicker'
import type { VenuePoint } from '../lib/venueSearch'
import type { VenueRegion } from '../api/types'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { useLoad } from '../hooks/useLoad'
import { dateToLocalInput, localTimeZoneLabel } from '../lib/time'
import { DEFAULT_RADIUS_M, validateActivityForm, type ActivityFormErrors } from '../lib/validation'

const HOUR_MS = 60 * 60_000

function defaultStartDate(): Date {
  const d = new Date(Date.now() + 30 * 60_000)
  d.setMinutes(Math.ceil(d.getMinutes() / 15) * 15, 0, 0)
  return d
}

/** An activity can be edited until the day before it starts (the server enforces this too). */
function isEditable(activity: Activity): boolean {
  const dayStart = new Date(activity.starts_at)
  dayStart.setHours(0, 0, 0, 0)
  return Date.now() < dayStart.getTime()
}

interface FormInitial { activity: Activity; studentIds: string[] }

function ActivityForm({ initial }: { initial?: FormInitial }) {
  const editing = initial !== undefined
  const students = useLoad(() => api.listStudents())
  const [name, setName] = useState(initial?.activity.name ?? '')
  const [venueName, setVenueName] = useState(initial?.activity.venue_name ?? '')
  const [startsAtLocal, setStartsAtLocal] = useState(() => dateToLocalInput(initial ? new Date(initial.activity.starts_at) : defaultStartDate()))
  const [endsAtLocal, setEndsAtLocal] = useState(() => dateToLocalInput(initial ? new Date(initial.activity.ends_at) : new Date(defaultStartDate().getTime() + 2 * HOUR_MS)))
  const [venueLocation, setVenueLocation] = useState<VenuePoint | null>(initial ? { lat: initial.activity.venue_lat, lng: initial.activity.venue_lng } : null)
  const [venueRegions, setVenueRegions] = useState<VenueRegion[] | null>(initial?.activity.venue_regions ?? null)
  const [changeVenue, setChangeVenue] = useState(!editing)
  const [radius, setRadius] = useState(String(initial?.activity.radius_m ?? 25))
  const [studentIds, setStudentIds] = useState<string[]>(initial?.studentIds ?? [])
  const [errors, setErrors] = useState<ActivityFormErrors>({})
  const [saved, setSaved] = useState<Activity | null>(null)

  const save = useAsyncAction(async () => {
    const first = venueRegions?.[0]
    const result = validateActivityForm({ name, venueName, startsAtLocal, endsAtLocal,
      latitude: venueLocation ? String(venueLocation.lat) : '', longitude: venueLocation ? String(venueLocation.lng) : '',
      radius: first ? String(first.radius_m) : radius, studentIds })
    setErrors(result.ok ? {} : result.errors)
    if (result.ok) {
      const input = { ...result.value, venue_regions: venueRegions ?? [] }
      setSaved(initial ? await api.updateActivity(initial.activity.id, input) : await api.createActivity(input))
    }
  })

  function onSubmit(e: FormEvent): void {
    e.preventDefault()
    void save.run()
  }

  function startChangingVenue(): void {
    // The new location has to be picked and confirmed on the map again.
    setVenueLocation(null)
    setVenueRegions(null)
    setChangeVenue(true)
  }

  if (saved) {
    return (
      <div className="stack">
        <h1>{editing ? 'Activity updated' : 'Activity created'}</h1>
        <Notice kind="ok">{saved.name} at {saved.venue_name} was {editing ? 'updated' : 'created'} for {saved.roster_count} students.</Notice>
        <BoundarySummary activity={saved} />
        <div className="btn-row">
          <Link className="btn btn-primary" to={`/activities/${saved.id}`}>Open register</Link>
          <Link className="btn" to="/activities">All activities</Link>
        </div>
      </div>
    )
  }

  const tz = localTimeZoneLabel()
  return (
    <form onSubmit={onSubmit} noValidate className="card" aria-labelledby="create-h">
      <h1 id="create-h">{editing ? 'Edit activity' : 'New activity'}</h1>
      {save.error ? <Notice kind="error">{save.error}</Notice> : null}
      <div className="form-grid">
        <Field label="Activity name" error={errors.name}>
          {(p) => <input id={p.id} value={name} aria-invalid={p.invalid} aria-describedby={p.describedBy} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Venue name" error={errors.venueName}>
          {(p) => <input id={p.id} value={venueName} aria-invalid={p.invalid} aria-describedby={p.describedBy} onChange={(e) => setVenueName(e.target.value)} />}
        </Field>
        <Field label={`Starts (${tz}, local time)`} error={errors.startsAt}>
          {(p) => <input id={p.id} type="datetime-local" value={startsAtLocal} aria-invalid={p.invalid} aria-describedby={p.describedBy} onChange={(e) => setStartsAtLocal(e.target.value)} />}
        </Field>
        <Field label={`Ends (${tz}, local time)`} error={errors.endsAt}>
          {(p) => <input id={p.id} type="datetime-local" value={endsAtLocal} aria-invalid={p.invalid} aria-describedby={p.describedBy} onChange={(e) => setEndsAtLocal(e.target.value)} />}
        </Field>
      </div>
      {changeVenue ? (
        <VenuePicker radius={Number(radius) >= 0 && Number(radius) <= 300 ? Number(radius) : DEFAULT_RADIUS_M}
          onRadiusChange={(metres) => setRadius(String(metres))}
          radiusError={errors.radius}
          error={errors.latitude || errors.longitude ? 'Choose a venue on the map and confirm its location.' : undefined}
          onChange={setVenueLocation} onRegionsChange={setVenueRegions} onName={(selectedName) => setVenueName((current) => current || selectedName)} />
      ) : initial ? (
        <section aria-label="Current venue" className="stack">
          <h2>Venue</h2>
          {errors.radius ? <Notice kind="error">{errors.radius}</Notice> : null}
          <BoundarySummary activity={initial.activity} />
          <div className="btn-row">
            <button type="button" className="btn btn-small" onClick={startChangingVenue}>Change venue</button>
          </div>
        </section>
      ) : null}
      {students.isLoading ? <Loading what="students" /> : null}
      {students.error ? <Notice kind="error">{students.error}</Notice> : null}
      {students.data ? <StudentPicker students={students.data} selected={studentIds} onChange={setStudentIds} error={errors.students} /> : null}
      <div className="btn-row">
        <button type="submit" className="btn btn-primary" disabled={save.isBusy}>
          {editing ? (save.isBusy ? 'Saving...' : 'Save changes') : (save.isBusy ? 'Creating...' : 'Create activity')}
        </button>
        <Link className="btn" to={initial ? `/activities/${initial.activity.id}` : '/activities'}>Cancel</Link>
      </div>
    </form>
  )
}

export function ActivityCreateScreen() {
  return <ActivityForm />
}

export function ActivityEditScreen() {
  const { activityId = '' } = useParams()
  const { data, isLoading, error } = useLoad(() => api.getRegister(activityId))
  if (isLoading && !data) return <Loading what="activity" />
  if (!data) {
    return (
      <div className="stack">
        <Notice kind="error">{error ?? 'Could not load this activity.'}</Notice>
        <Link to="/activities">Back to activities</Link>
      </div>
    )
  }
  if (!isEditable(data.activity)) {
    return (
      <div className="stack">
        <h1>Edit activity</h1>
        <Notice kind="warn">This activity can only be edited up to the day before it starts.</Notice>
        <Link to={`/activities/${data.activity.id}`}>Back to the register</Link>
      </div>
    )
  }
  return <ActivityForm initial={{ activity: data.activity, studentIds: data.rows.map((r) => r.student.id) }} />
}
