import { render, screen } from '@testing-library/react'
import type { AttendanceEvent } from '../src/api'
import { makeEvent } from '../src/api/fixtureData'
import { EventDetail } from '../src/components/EventCell'

const base = (): AttendanceEvent => makeEvent({ id: 'e1', student: 'u_s1', activity: 'act_1', action: 'check_in', at: Date.parse('2026-06-01T10:00:00Z') })

describe('Code scanned mark', () => {
  it('is shown when presence_verified is true', () => {
    render(<EventDetail event={{ ...base(), presence_verified: true }} />)
    expect(screen.getByText('Code scanned')).toBeInTheDocument()
  })
  it('is absent when presence_verified is false', () => {
    render(<EventDetail event={{ ...base(), presence_verified: false }} />)
    expect(screen.queryByText('Code scanned')).not.toBeInTheDocument()
  })
  it('is absent when the field is missing (older backend)', () => {
    const legacy = base()
    delete legacy.presence_verified
    render(<EventDetail event={legacy} />)
    expect(screen.queryByText('Code scanned')).not.toBeInTheDocument()
  })
})
