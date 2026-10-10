import { useEffect, useState } from 'react'
import { api, messageForError } from '../api'

/** Fetches (mount with key={enrollmentId} so state resets per enrollment)  the photo with the bearer token as a blob; revokes the object URL on unmount. */
export function EnrollmentPhoto({ enrollmentId, studentName }: { enrollmentId: string; studentName: string }) {
  const [src, setSrc] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    let url: string | null = null
    api.enrollmentPhoto(enrollmentId)
      .then((blob) => {
        if (cancelled) return
        url = URL.createObjectURL(blob)
        setSrc(url)
      })
      .catch((err: unknown) => { if (!cancelled) setError(messageForError(err)) })
    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [enrollmentId])

  if (error) return <div className="enroll-photo"><span className="field-error">Photo unavailable. {error}</span></div>
  if (!src) return <div className="enroll-photo" role="status"><span className="muted">Loading photo...</span></div>
  return <img className="enroll-photo" src={src} alt={`Enrollment photograph of ${studentName}`} />
}
