import { useState } from 'react'
import type { AttendanceRecord } from '../api'
import { reasonLabel } from '../lib/labels'
import { CheckpointsCell, EventDetail, NoneRecorded } from './EventCell'
import { DecisionForm } from './DecisionForm'
import { DecisionNote } from './DecisionNote'
import { FacultyObservations } from './FacultyObservations'
import { ObservationForm } from './ObservationForm'
import { StatusBadge } from './StatusBadge'

interface Props {
  record: AttendanceRecord
  onChanged: () => void
}

type Panel = 'decision' | 'observation' | null

export function RegisterRow({ record, onChanged }: Props) {
  const [panel, setPanel] = useState<Panel>(null)
  const { student } = record
  const done = (): void => { setPanel(null); onChanged() }

  return (
    <li className="reg-row">
      <div className="cell cell-student">
        <h3>{student.full_name}</h3>
        <StatusBadge status={record.status} />
        {record.missing_checkout ? <p className="marker marker-warn">Missing checkout: incomplete</p> : null}
        {record.review_reasons.length > 0 ? (
          <ul className="reasons" aria-label={`Review reasons for ${student.full_name}`}>
            {record.review_reasons.map((r) => <li key={r}>{reasonLabel(r)}</li>)}
          </ul>
        ) : null}
      </div>
      <div className="cell">
        <span className="cell-label">Arrival</span>
        {record.check_in ? <EventDetail event={record.check_in} /> : <NoneRecorded text="None recorded" />}
      </div>
      <div className="cell">
        <span className="cell-label">Last recorded checkpoint</span>
        <CheckpointsCell checkpoints={record.checkpoints} />
      </div>
      <div className="cell">
        <span className="cell-label">Departure</span>
        {record.check_out ? <EventDetail event={record.check_out} /> : <NoneRecorded text="None recorded" />}
      </div>
      <div className="cell cell-wide">
        <span className="cell-label">Faculty decision</span>
        <DecisionNote record={record} />
        <FacultyObservations observations={record.observations} />
        {panel === null ? (
          <div className="btn-row">
            <button type="button" className="btn btn-small" onClick={() => setPanel('decision')}
              aria-label={`Confirm or decline attendance for ${student.full_name}`}>
              Confirm or decline
            </button>
            <button type="button" className="btn btn-small" onClick={() => setPanel('observation')}
              aria-label={`Record in-person observation for ${student.full_name}`}>
              Record observation
            </button>
          </div>
        ) : null}
        {panel === 'decision' ? (
          <DecisionForm activityId={record.activity_id} studentId={student.id} studentName={student.full_name}
            onDone={done} onCancel={() => setPanel(null)} />
        ) : null}
        {panel === 'observation' ? (
          <ObservationForm activityId={record.activity_id} studentId={student.id} studentName={student.full_name}
            onDone={done} onCancel={() => setPanel(null)} />
        ) : null}
      </div>
    </li>
  )
}
