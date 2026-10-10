import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
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

export function ActivityCreateScreen() {
  const students = useLoad(() => api.listStudents())
  const [name, setName] = useState('')
  const [venueName, setVenueName] = useState('')
  const [startsAtLocal, setStartsAtLocal] = useState(() => dateToLocalInput(defaultStartDate()))
  const [endsAtLocal, setEndsAtLocal] = useState(() => dateToLocalInput(new Date(defaultStartDate().getTime() + 2 * HOUR_MS)))
  const [venueLocation, setVenueLocation] = useState<VenuePoint | null>(null)
  const [venueRegions, setVenueRegions] = useState<VenueRegion[] | null>(null)
  const [radius, setRadius] = useState('25')
  const [studentIds, setStudentIds] = useState<string[]>([])
  const [errors, setErrors] = useState<ActivityFormErrors>({})
  const [created, setCreated] = useState<Activity | null>(null)

  const save = useAsyncAction(async () => {
    const first = venueRegions?.[0]
    const result = validateActivityForm({ name, venueName, startsAtLocal, endsAtLocal,
      latitude: venueLocation ? String(venueLocation.lat) : '', longitude: venueLocation ? String(venueLocation.lng) : '',
      radius: first ? String(first.radius_m) : radius, studentIds })
    setErrors(result.ok ? {} : result.errors)
    if (result.ok) setCreated(await api.createActivity({ ...result.value, venue_regions: venueRegions ?? [] }))
  })

  function onSubmit(e: FormEvent): void {
    e.preventDefault()
    void save.run()
  }

  if (created) {
    return (
      <div className="stack">
        <h1>Activity created</h1>
        <Notice kind="ok">{created.name} at {created.venue_name} was created for {created.roster_count} students.</Notice>
        <BoundarySummary activity={created} />
        <div className="btn-row">
          <Link className="btn btn-primary" to={`/activities/${created.id}`}>Open register</Link>
          <Link className="btn" to="/activities">All activities</Link>
        </div>
      </div>
    )
  }

  const tz = localTimeZoneLabel()
  return (
    <form onSubmit={onSubmit} noValidate className="card" aria-labelledby="create-h">
      <h1 id="create-h">New activity</h1>
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
      <VenuePicker radius={Number(radius) >= 0 && Number(radius) <= 300 ? Number(radius) : DEFAULT_RADIUS_M}
        onRadiusChange={(metres) => setRadius(String(metres))}
        radiusError={errors.radius}
        error={errors.latitude || errors.longitude ? 'Choose a venue on the map and confirm its location.' : undefined}
        onChange={setVenueLocation} onRegionsChange={setVenueRegions} onName={(selectedName) => setVenueName((current) => current || selectedName)} />
      {students.isLoading ? <Loading what="students" /> : null}
      {students.error ? <Notice kind="error">{students.error}</Notice> : null}
      {students.data ? <StudentPicker students={students.data} selected={studentIds} onChange={setStudentIds} error={errors.students} /> : null}
      <div className="btn-row">
        <button type="submit" className="btn btn-primary" disabled={save.isBusy}>{save.isBusy ? 'Creating...' : 'Create activity'}</button>
        <Link className="btn" to="/activities">Cancel</Link>
      </div>
    </form>
  )
}
