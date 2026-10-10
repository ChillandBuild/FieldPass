import { useState } from 'react'
import type { Enrollment } from '../api'
import { api, isApiError } from '../api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { formatDateTime } from '../lib/time'
import { validateRequiredText } from '../lib/validation'
import { EnrollmentPhoto } from './EnrollmentPhoto'
import { Field } from './Field'
import { Notice } from './Notice'

interface Props {
  enrollment: Enrollment
  onDecided: (updated: Enrollment) => void
  /** Called when the server no longer knows this enrollment id: the student uploaded a newer photo. */
  onStale?: () => void
}

export function EnrollmentCard({ enrollment, onDecided, onStale }: Props) {
  const [confirmed, setConfirmed] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()
  const id = enrollment.id ?? ''
  const name = enrollment.student.full_name

  /** Runs a decision; a not_found means a newer upload replaced this enrollment, which is not an error to show. */
  async function decide(call: () => Promise<Enrollment>): Promise<void> {
    try {
      onDecided(await call())
    } catch (err) {
      if (onStale && isApiError(err) && err.code === 'not_found') onStale()
      else throw err
    }
  }

  const approve = useAsyncAction(() => decide(() => api.approveEnrollment(id)))
  const reject = useAsyncAction(() => decide(() => api.rejectEnrollment(id, reason.trim())))

  function submitReject(): void {
    const problem = validateRequiredText(reason, 'a reason for rejecting this photo')
    setReasonError(problem ?? undefined)
    if (!problem) void reject.run()
  }

  const error = approve.error ?? reject.error
  const busy = approve.isBusy || reject.isBusy
  return (
    <li className="card enroll-card stack">
      <EnrollmentPhoto enrollmentId={id} studentName={name} />
      <div>
        <h2>{name}</h2>
        <p className="muted">{enrollment.student.username} - submitted {formatDateTime(enrollment.submitted_at)}</p>
      </div>
      <p className="identity-note">
        Approving means you, as faculty, have confirmed this photograph shows this student. It will be used as the
        reference for later comparisons.
      </p>
      {error ? <Notice kind="error">{error}</Notice> : null}
      <div className="check">
        <input id={`idc-${id}`} type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        <label htmlFor={`idc-${id}`}>I have confirmed this person&apos;s identity</label>
      </div>
      <div className="btn-row">
        <button type="button" className="btn btn-primary" disabled={!confirmed || busy}
          onClick={() => { void approve.run() }}>
          {approve.isBusy ? 'Approving...' : 'Approve photo'}
        </button>
        <button type="button" className="btn btn-danger" disabled={busy} aria-expanded={rejecting}
          onClick={() => setRejecting((v) => !v)}>
          Reject...
        </button>
      </div>
      {rejecting ? (
        <div>
          <Field label="Reason for rejecting (required)" error={reasonError}>
            {(p) => <textarea id={p.id} value={reason} aria-invalid={p.invalid} aria-describedby={p.describedBy}
              onChange={(e) => setReason(e.target.value)} />}
          </Field>
          <button type="button" className="btn btn-danger" disabled={busy} onClick={submitReject}>
            {reject.isBusy ? 'Rejecting...' : 'Confirm rejection'}
          </button>
        </div>
      ) : null}
    </li>
  )
}
