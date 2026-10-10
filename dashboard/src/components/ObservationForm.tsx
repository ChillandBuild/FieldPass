import { useState } from 'react'
import { api } from '../api'
import type { Observation, ObservationType } from '../api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { OBSERVATION_LABELS, OBSERVATION_TYPES } from '../lib/labels'
import { validateRequiredText } from '../lib/validation'
import { Field } from './Field'
import { Notice } from './Notice'

interface Props {
  activityId: string
  studentId: string
  studentName: string
  onDone: (obs: Observation) => void
  onCancel: () => void
}

export function ObservationForm({ activityId, studentId, studentName, onDone, onCancel }: Props) {
  const [type, setType] = useState<ObservationType>('present')
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState<string | undefined>()
  const save = useAsyncAction(async () => {
    onDone(await api.addObservation(activityId, { student_id: studentId, observation_type: type, note: note.trim() }))
  })

  function submit(): void {
    const problem = validateRequiredText(note, 'a note describing what you saw')
    setNoteError(problem ?? undefined)
    if (!problem) void save.run()
  }

  return (
    <div className="row-form" role="group" aria-label={`In-person observation for ${studentName}`}>
      {save.error ? <Notice kind="error">{save.error}</Notice> : null}
      <Field label="What you saw in person">
        {(p) => (
          <select id={p.id} value={type} onChange={(e) => setType(e.target.value as ObservationType)}>
            {OBSERVATION_TYPES.map((t) => <option key={t} value={t}>{OBSERVATION_LABELS[t]}</option>)}
          </select>
        )}
      </Field>
      <Field label="Note (required)" error={noteError}>
        {(p) => <textarea id={p.id} value={note} aria-invalid={p.invalid} aria-describedby={p.describedBy}
          onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <div className="btn-row">
        <button type="button" className="btn btn-primary btn-small" disabled={save.isBusy} onClick={submit}>
          {save.isBusy ? 'Saving...' : 'Save observation'}
        </button>
        <button type="button" className="btn btn-small" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}
