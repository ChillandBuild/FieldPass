import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { EnrollmentCard } from '../src/components/EnrollmentCard'
import { EnrollmentsScreen } from '../src/screens/EnrollmentsScreen'

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  const fx = await import('../src/api/fixtureClient')
  return { ...real, api: fx.fixtureApi }
})

const STALE_MESSAGE = 'This student uploaded a new photo. The list has been refreshed.'
const gone = () => new ApiError('not_found', 'That enrollment does not exist.', 404)

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => { vi.restoreAllMocks() })

async function firstPending() {
  const [enrollment] = await fixtureApi.listEnrollments('pending')
  if (!enrollment) throw new Error('no fixture enrollment')
  return enrollment
}

describe('stale enrollment (the student uploaded a new photo)', () => {
  it('card: approving a replaced enrollment calls onStale and shows no raw error', async () => {
    vi.spyOn(fixtureApi, 'approveEnrollment').mockRejectedValue(gone())
    const onStale = vi.fn()
    const onDecided = vi.fn()
    render(<ul><EnrollmentCard enrollment={await firstPending()} onDecided={onDecided} onStale={onStale} /></ul>)
    await userEvent.click(screen.getByLabelText("I have confirmed this person's identity"))
    await userEvent.click(screen.getByRole('button', { name: 'Approve photo' }))
    await vi.waitFor(() => expect(onStale).toHaveBeenCalledTimes(1))
    expect(onDecided).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('card: rejecting a replaced enrollment calls onStale too', async () => {
    vi.spyOn(fixtureApi, 'rejectEnrollment').mockRejectedValue(gone())
    const onStale = vi.fn()
    render(<ul><EnrollmentCard enrollment={await firstPending()} onDecided={vi.fn()} onStale={onStale} /></ul>)
    await userEvent.click(screen.getByRole('button', { name: /Reject/ }))
    await userEvent.type(screen.getByLabelText(/Reason for rejecting/), 'Too dark')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }))
    await vi.waitFor(() => expect(onStale).toHaveBeenCalledTimes(1))
  })

  it('card: other errors still show their friendly message and do not count as stale', async () => {
    vi.spyOn(fixtureApi, 'approveEnrollment').mockRejectedValue(new ApiError('already_decided', 'x', 409))
    const onStale = vi.fn()
    render(<ul><EnrollmentCard enrollment={await firstPending()} onDecided={vi.fn()} onStale={onStale} /></ul>)
    await userEvent.click(screen.getByLabelText("I have confirmed this person's identity"))
    await userEvent.click(screen.getByRole('button', { name: 'Approve photo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already been approved or rejected')
    expect(onStale).not.toHaveBeenCalled()
  })

  it('screen: shows the plain message and reloads the pending list', async () => {
    const list = vi.spyOn(fixtureApi, 'listEnrollments')
    vi.spyOn(fixtureApi, 'approveEnrollment').mockRejectedValue(gone())
    render(<EnrollmentsScreen />)
    const approve = await screen.findAllByRole('button', { name: 'Approve photo' })
    await userEvent.click(screen.getAllByLabelText("I have confirmed this person's identity")[0] as HTMLElement)
    await userEvent.click(approve[0] as HTMLElement)
    expect(await screen.findByText(STALE_MESSAGE)).toBeInTheDocument()
    await vi.waitFor(() => expect(list.mock.calls.length).toBeGreaterThanOrEqual(2))
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument()
  })
})
