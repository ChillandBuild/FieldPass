import { useState } from 'react'
import { api } from '../api'
import type { Enrollment } from '../api'
import { EnrollmentCard } from '../components/EnrollmentCard'
import { Empty, Loading, Notice } from '../components/Notice'
import { useLoad } from '../hooks/useLoad'

export const STALE_ENROLLMENT_MESSAGE = 'This student uploaded a new photo. The list has been refreshed.'

export function EnrollmentsScreen() {
  const { data, isLoading, error, reload } = useLoad(() => api.listEnrollments('pending'))
  const [decidedIds, setDecidedIds] = useState<readonly string[]>([])
  const [lastDecision, setLastDecision] = useState<string | null>(null)
  const [staleNotice, setStaleNotice] = useState(false)

  const items = (data ?? []).filter((e) => e.id === null || !decidedIds.includes(e.id))

  function handleStale(): void {
    setLastDecision(null)
    setStaleNotice(true)
    reload()
  }

  function handleDecided(updated: Enrollment): void {
    setStaleNotice(false)
    if (updated.id) setDecidedIds((prev) => [...prev, updated.id as string])
    const verb = updated.status === 'approved' ? 'Approved' : 'Rejected'
    setLastDecision(`${verb} the enrollment photo for ${updated.student.full_name}.`)
  }

  return (
    <>
      <div className="page-head">
        <h1>Enrollment photos waiting for approval</h1>
        <button type="button" className="btn btn-small" onClick={reload} disabled={isLoading}>Refresh</button>
      </div>
      {lastDecision ? <Notice kind="ok">{lastDecision}</Notice> : null}
      {staleNotice ? <Notice kind="warn">{STALE_ENROLLMENT_MESSAGE}</Notice> : null}
      {error ? <Notice kind="error">{error}</Notice> : null}
      {isLoading && items.length === 0 ? <Loading what="pending enrollments" /> : null}
      {!isLoading && !error && items.length === 0 ? <Empty>No enrollment photos are waiting for approval.</Empty> : null}
      <ul className="list-plain enroll-grid">
        {items.map((e) => <EnrollmentCard key={e.id} enrollment={e} onDecided={handleDecided} onStale={handleStale} />)}
      </ul>
    </>
  )
}
