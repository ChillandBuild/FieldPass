import { useState } from 'react'
import { api } from '../api'
import type { AttendanceRecord, DecisionKind } from '../api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { validateRequiredText } from '../lib/validation'
import { Field } from './Field'
import { Notice } from './Notice'

interface Props {
  activityId: string
  studentId: string
  studentName: string
  onDone: (record: AttendanceRecord) => void
  onCancel: () => void
}

export function DecisionForm({ activityId, studentId, studentName, onDone, onCancel }: Props) {
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()
  const save = useAsyncAction(async (decision: DecisionKind) => {
    onDone(await api.decide(activityId, studentId, { decision, reason: reason.trim() }))
  })

  function submit(decision: DecisionKind): void {
    const problem = validateRequiredText(reason, 'a reason for your decision')
    setReasonError(problem ?? undefined)
    if (!problem) void save.run(decision)
  }

  return (
    <div className="row-form" role="group" aria-label={`Decision for ${studentName}`}>
      {save.error ? <Notice kind="error">{save.error}</Notice> : null}
      <Field label="Reason (required)" error={reasonError} hint="Recorded with your name and the time.">
        {(p) => <textarea id={p.id} value={reason} aria-invalid={p.invalid} aria-describedby={p.describedBy}
          onChange={(e) => setReason(e.target.value)} />}
      </Field>
      <div className="btn-row">
        <button type="button" className="btn btn-primary btn-small" disabled={save.isBusy} onClick={() => submit('confirmed')}>
          Confirm attendance
        </button>
        <button type="button" className="btn btn-danger btn-small" disabled={save.isBusy} onClick={() => submit('not_confirmed')}>
          Decline attendance
        </button>
        <button type="button" className="btn btn-small" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}
