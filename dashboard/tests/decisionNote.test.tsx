import { render, screen } from '@testing-library/react'
import type { AttendanceRecord, Decision } from '../src/api'
import { DecisionNote } from '../src/components/DecisionNote'

const decline: Decision = {
  id: 'dec_1', decision: 'not_confirmed', reason: 'Selfie looked different', actor_name: 'Dr. Meera Rao',
  decided_at: '2026-10-10T10:00:00Z', is_current: false,
}
const confirm: Decision = {
  id: 'dec_2', decision: 'confirmed', reason: 'Checked in person', actor_name: 'Dr. Meera Rao',
  decided_at: '2026-10-10T11:00:00Z', is_current: true,
}

function record(patch: Partial<AttendanceRecord>): AttendanceRecord {
  return {
    activity_id: 'act_1', activity_name: 'A', student: { id: 'u_s1', username: 'student1', full_name: 'Asha Verma', role: 'student' },
    status: null, check_in: null, checkpoints: [], check_out: null, missing_checkout: false, complete: false,
    review_reasons: [], decision: null, decision_history: [], observations: [], next_actions: [], ...patch,
  }
}

describe('DecisionNote', () => {
  it('shows the newest decision and keeps the earlier one in history', () => {
    render(<DecisionNote record={record({ decision: confirm, decision_history: [confirm, { ...decline }] })} />)
    expect(screen.getByText('Reason: Checked in person')).toBeInTheDocument()
    expect(screen.getByText('Earlier decisions (1)')).toBeInTheDocument()
    expect(screen.getByText('Reason: Selfie looked different')).toBeInTheDocument()
  })

  it('marks the newest history entry outdated when the server sends decision null', () => {
    const outdated = { ...confirm, is_current: false }
    render(<DecisionNote record={record({ decision: null, decision_history: [outdated, decline] })} />)
    expect(screen.getByText(/Outdated decision, new evidence arrived/)).toBeInTheDocument()
    expect(screen.getByText('Reason: Checked in person')).toBeInTheDocument()
  })

  it('says so when there is no decision at all', () => {
    render(<DecisionNote record={record({})} />)
    expect(screen.getByText('No faculty decision yet.')).toBeInTheDocument()
  })
})
