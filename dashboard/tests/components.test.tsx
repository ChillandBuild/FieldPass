import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { DecisionForm } from '../src/components/DecisionForm'
import { EnrollmentCard } from '../src/components/EnrollmentCard'

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  const fx = await import('../src/api/fixtureClient')
  return { ...real, api: fx.fixtureApi }
})

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

describe('DecisionForm', () => {
  it('blocks both decisions until a reason is entered', async () => {
    const onDone = vi.fn()
    render(<DecisionForm activityId="act_1" studentId="u_s2" studentName="Rohan Iyer" onDone={onDone} onCancel={() => undefined} />)
    await userEvent.click(screen.getByRole('button', { name: 'Confirm attendance' }))
    expect(screen.getByText('Enter a reason for your decision.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Decline attendance' }))
    expect(onDone).not.toHaveBeenCalled()
  })

  it('submits a decline with the reason', async () => {
    const onDone = vi.fn()
    render(<DecisionForm activityId="act_1" studentId="u_s2" studentName="Rohan Iyer" onDone={onDone} onCancel={() => undefined} />)
    await userEvent.type(screen.getByLabelText(/Reason/), 'Not at the gate')
    await userEvent.click(screen.getByRole('button', { name: 'Decline attendance' }))
    await vi.waitFor(() => expect(onDone).toHaveBeenCalledTimes(1))
    expect(onDone.mock.calls[0]?.[0].status).toBe('not_confirmed')
  })
})

describe('EnrollmentCard', () => {
  it('keeps Approve disabled until identity is confirmed, and Reject needs a reason', async () => {
    const [enrollment] = await fixtureApi.listEnrollments('pending')
    if (!enrollment) throw new Error('no fixture enrollment')
    const onDecided = vi.fn()
    URL.createObjectURL = vi.fn(() => 'blob:x')
    URL.revokeObjectURL = vi.fn()
    render(<ul><EnrollmentCard enrollment={enrollment} onDecided={onDecided} /></ul>)
    const approve = screen.getByRole('button', { name: 'Approve photo' })
    expect(approve).toBeDisabled()
    await userEvent.click(screen.getByLabelText("I have confirmed this person's identity"))
    expect(approve).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: /Reject/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }))
    expect(screen.getByText(/Enter a reason for rejecting/)).toBeInTheDocument()
    expect(onDecided).not.toHaveBeenCalled()
  })
})

describe('fixture data coverage', () => {
  it('has pending enrollments and every register state', async () => {
    expect((await api.listEnrollments('pending')).length).toBeGreaterThan(0)
    const rows = [
      ...(await api.getRegister('act_1')).rows,
      ...(await api.getRegister('act_2')).rows,
    ]
    const statuses = new Set(rows.map((r) => r.status))
    for (const s of ['recorded', 'needs_review', 'confirmed', 'not_confirmed']) expect(statuses.has(s as never)).toBe(true)
    expect(rows.some((r) => r.missing_checkout)).toBe(true)
    expect(rows.some((r) => r.decision && !r.decision.is_current)).toBe(true)
    expect(rows.some((r) => r.observations.length > 0)).toBe(true)
  })
})
