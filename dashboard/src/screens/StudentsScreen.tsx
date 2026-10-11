import { useRef, useState } from 'react'
import { api, messageForError } from '../api'
import type { Student } from '../api'
import { Empty, Loading, Notice } from '../components/Notice'
import { useLoad } from '../hooks/useLoad'
import { formatDateTime } from '../lib/time'

const NEXT_SIGN_IN = 'Their next sign-in links the phone they use.'

interface RowProps {
  student: Student
  isAsking: boolean
  isBusy: boolean
  onAsk: () => void
  onCancel: () => void
  onConfirm: () => void
  onUpload: (file: File) => void
}

function StudentRow({ student, isAsking, isBusy, onAsk, onCancel, onConfirm, onUpload }: RowProps) {
  const bound = student.device_bound_at
  const picker = useRef<HTMLInputElement>(null)
  return (
    <li className="card activity-row">
      <div>
        <h2>{student.full_name}</h2>
        <p className="muted">{student.username}</p>
        <p>{bound ? `Phone linked ${formatDateTime(bound)}` : `No phone linked yet. ${NEXT_SIGN_IN}`}</p>
      </div>
      <input
        ref={picker}
        type="file"
        accept="image/jpeg,image/png"
        hidden
        aria-label={`Enrollment photo file for ${student.full_name}`}
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) onUpload(file)
        }}
      />
      <button
        type="button"
        className="btn"
        onClick={() => picker.current?.click()}
        disabled={isBusy}
        aria-label={`Upload enrollment photo for ${student.full_name}`}
      >
        Upload photo
      </button>
      {bound && !isAsking ? (
        <button type="button" className="btn" onClick={onAsk} aria-label={`Reset phone for ${student.full_name}`}>
          Reset phone
        </button>
      ) : null}
      {bound && isAsking ? (
        <div className="btn-row">
          <span>Unlink {student.full_name}&apos;s phone?</span>
          <button type="button" className="btn btn-primary" onClick={onConfirm} disabled={isBusy}>Yes, unlink</button>
          <button type="button" className="btn" onClick={onCancel} disabled={isBusy}>Cancel</button>
        </div>
      ) : null}
    </li>
  )
}

/** Faculty see which phone each student is locked to, and unlink it when a student changes phone. */
export function StudentsScreen() {
  const { data, isLoading, error, reload } = useLoad(() => api.listStudents())
  const [askingId, setAskingId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  async function confirmReset(student: Student): Promise<void> {
    setBusy(true)
    setFailure(null)
    try {
      await api.resetDevice(student.id)
      setDone(`Unlinked ${student.full_name}'s phone. ${NEXT_SIGN_IN}`)
      setAskingId(null)
      reload()
    } catch (err) {
      setDone(null)
      setFailure(messageForError(err))
    } finally {
      setBusy(false)
    }
  }

  async function uploadPhoto(student: Student, file: File): Promise<void> {
    setBusy(true)
    setFailure(null)
    try {
      await api.uploadEnrollment(student.id, file)
      setDone(`Uploaded a photo for ${student.full_name}. Approve it on the Enrollments page before they can check in.`)
    } catch (err) {
      setDone(null)
      setFailure(messageForError(err))
    } finally {
      setBusy(false)
    }
  }

  const students = data ?? []
  return (
    <>
      <div className="page-head">
        <h1>Students and their phones</h1>
        <button type="button" className="btn btn-small" onClick={reload} disabled={isLoading}>Refresh</button>
      </div>
      <p className="muted">Each student can check in from one phone only. Reset it when a student gets a new phone or reinstalls the app.</p>
      {done ? <Notice kind="ok">{done}</Notice> : null}
      {failure ? <Notice kind="error">{failure}</Notice> : null}
      {error ? <Notice kind="error">{error}</Notice> : null}
      {isLoading && !data ? <Loading what="students" /> : null}
      {data && students.length === 0 ? <Empty>No students yet.</Empty> : null}
      <ul className="list-plain activity-list">
        {students.map((s) => (
          <StudentRow
            key={s.id}
            student={s}
            isAsking={askingId === s.id}
            isBusy={busy}
            onAsk={() => { setAskingId(s.id); setDone(null); setFailure(null) }}
            onCancel={() => setAskingId(null)}
            onConfirm={() => { void confirmReset(s) }}
            onUpload={(file) => { void uploadPhoto(s, file) }}
          />
        ))}
      </ul>
    </>
  )
}
