import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { RegisterScreen } from '../src/screens/RegisterScreen'

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  const fx = await import('../src/api/fixtureClient')
  return { ...real, api: fx.fixtureApi }
})

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

function renderRegister(id: string) {
  render(
    <MemoryRouter initialEntries={[`/activities/${id}`]}>
      <Routes><Route path="/activities/:activityId" element={<RegisterScreen />} /></Routes>
    </MemoryRouter>,
  )
}

describe('register presence code', () => {
  it('offers "Show presence code" next to the checkpoint control', async () => {
    renderRegister('act_1')
    expect(await screen.findByRole('button', { name: 'Show presence code' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Checkpoint window' })).toBeInTheDocument()
  })

  it('marks only the events whose code was scanned', async () => {
    renderRegister('act_1')
    await screen.findByText('Asha Verma')
    // Fixture: Asha and Fatima scanned the code at arrival; Rohan did not.
    expect(screen.getAllByText('Code scanned')).toHaveLength(2)
  })
})
