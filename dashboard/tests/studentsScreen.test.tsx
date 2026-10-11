import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { StudentsScreen } from '../src/screens/StudentsScreen'

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  const fx = await import('../src/api/fixtureClient')
  return { ...real, api: fx.fixtureApi }
})

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

afterEach(() => { vi.restoreAllMocks() })

const row = async (name: string) => (await screen.findByRole('heading', { name })).closest('li') as HTMLElement

describe('Students page', () => {
  it('shows who has a phone linked', async () => {
    render(<StudentsScreen />)
    expect(within(await row('Asha Verma')).getByText(/^Phone linked/)).toBeInTheDocument()
    const kabir = await row('Kabir Menon')
    expect(within(kabir).getByText(/No phone linked yet/)).toBeInTheDocument()
    expect(within(kabir).queryByRole('button', { name: /Reset phone/ })).toBeNull()
  })

  it('reset asks once more, then unlinks and says so', async () => {
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    expect(screen.getByText("Unlink Asha Verma's phone?")).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Yes, unlink' }))
    expect(await screen.findByText("Unlinked Asha Verma's phone. Their next sign-in links the phone they use.")).toBeInTheDocument()
    await vi.waitFor(async () => expect(within(await row('Asha Verma')).getByText(/No phone linked yet/)).toBeInTheDocument())
  })

  it('cancel leaves the phone linked', async () => {
    const spy = vi.spyOn(fixtureApi, 'resetDevice')
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(spy).not.toHaveBeenCalled()
    expect(within(await row('Asha Verma')).getByText(/^Phone linked/)).toBeInTheDocument()
  })

  it('a failed reset shows the friendly error, not server text', async () => {
    vi.spyOn(fixtureApi, 'resetDevice').mockRejectedValue(new ApiError('not_found', 'raw server text', 404))
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, unlink' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That item no longer exists.')
    expect(screen.queryByText('raw server text')).toBeNull()
  })
})
