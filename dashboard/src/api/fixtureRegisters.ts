import type { Activity, AttendanceRecord } from './types'
import { STUDENTS, emptyRecord, makeDecision, makeEvent, makeObservation } from './fixtureData'

const MIN = 60_000

function finish(r: AttendanceRecord): AttendanceRecord {
  const events = [r.check_in, ...r.checkpoints, r.check_out].filter((e) => e !== null)
  const reasons = Array.from(new Set(events.flatMap((e) => e.review_reasons)))
  const current = r.decision?.is_current ? r.decision : null
  const status = current
    ? current.decision
    : events.length === 0 ? null : events.some((e) => e.status === 'needs_review') ? 'needs_review' : 'recorded'
  return { ...r, review_reasons: reasons, status, complete: r.check_in !== null && r.check_out !== null }
}

/** Ongoing activity: recorded, needs_review (two reasons), confirmed, nothing yet. */
function ongoing(a: Activity, now: number): AttendanceRecord[] {
  const t = Date.parse(a.starts_at)
  const [asha, rohan, fatima, kabir] = STUDENTS as [typeof STUDENTS[0], typeof STUDENTS[0], typeof STUDENTS[0], typeof STUDENTS[0]]
  const ev = (id: string, s: string, action: 'check_in' | 'checkpoint' | 'check_out', at: number, extra = {}) =>
    makeEvent({ id, student: s, activity: a.id, action, at, ...extra })
  return [
    finish({
      ...emptyRecord(a, asha), next_actions: [],
      check_in: ev('e1', asha.id, 'check_in', t + 4 * MIN, { presence: true }),
      checkpoints: [ev('e2', asha.id, 'checkpoint', now - 36 * MIN, { checkpointId: 'cp_1' })],
      observations: [makeObservation('o1', a.id, asha.id, 'present', 'Signed the paper sheet at the gate', now - 20 * MIN)],
    }),
    finish({
      ...emptyRecord(a, rohan),
      check_in: ev('e3', rohan.id, 'check_in', t + 9 * MIN, { location: 'uncertain', reasons: ['location_uncertain'] }),
      checkpoints: [ev('e4', rohan.id, 'checkpoint', now - 35 * MIN, { checkpointId: 'cp_1', face: 'no_match', reasons: ['face_mismatch'] })],
    }),
    finish({
      ...emptyRecord(a, fatima),
      check_in: ev('e5', fatima.id, 'check_in', t + 6 * MIN, { presence: true }),
      decision: makeDecision('d1', 'confirmed', 'Seen on site with the group', now - 10 * MIN, true),
      decision_history: [makeDecision('d1', 'confirmed', 'Seen on site with the group', now - 10 * MIN, true)],
    }),
    finish({ ...emptyRecord(a, kabir) }),
  ]
}

/** Past activity: missing checkout, outdated decision, declined, complete. */
function past(a: Activity): AttendanceRecord[] {
  const t = Date.parse(a.starts_at)
  const [asha, rohan, fatima] = STUDENTS as [typeof STUDENTS[0], typeof STUDENTS[0], typeof STUDENTS[0]]
  const ev = (id: string, s: string, action: 'check_in' | 'check_out', at: number, extra = {}) =>
    makeEvent({ id, student: s, activity: a.id, action, at, ...extra })
  const old = makeDecision('d2', 'confirmed', 'Present for the briefing', t + 100 * MIN, false)
  return [
    finish({
      ...emptyRecord(a, asha),
      check_in: ev('p1', asha.id, 'check_in', t + 3 * MIN),
      check_out: ev('p2', asha.id, 'check_out', t + 115 * MIN),
    }),
    finish({
      ...emptyRecord(a, rohan), missing_checkout: true,
      check_in: ev('p3', rohan.id, 'check_in', t + 5 * MIN),
      decision: old, decision_history: [old],
      observations: [makeObservation('o2', a.id, rohan.id, 'left_early', 'Told me he had to leave at the break', t + 70 * MIN)],
    }),
    finish({
      ...emptyRecord(a, fatima),
      check_in: ev('p4', fatima.id, 'check_in', t + 8 * MIN, { location: 'outside', reasons: ['location_outside'] }),
      decision: makeDecision('d3', 'not_confirmed', 'Check-in was 420 m away and she was not in the room', t + 130 * MIN, true),
      decision_history: [makeDecision('d3', 'not_confirmed', 'Check-in was 420 m away and she was not in the room', t + 130 * MIN, true)],
      observations: [makeObservation('o3', a.id, fatima.id, 'absent', 'Not in the room at roll call', t + 20 * MIN)],
    }),
  ]
}

export function seedRegisterRows(a: Activity, now: number): AttendanceRecord[] {
  return a.id === 'act_1' ? ongoing(a, now) : past(a)
}

export { finish as recomputeRecord }
