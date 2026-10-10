import { render, screen, within } from '@testing-library/react'
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

it.each([
  // Current confirmed and declined decisions must not be counted as awaiting review,
  // even when the record retains flagged evidence. Arrival is independent of decision.
  { id: 'act_1', roster: 4, arrivals: 3, review: 1 },
  { id: 'act_2', roster: 3, arrivals: 3, review: 0 },
])('summarizes $id without treating recorded evidence as a faculty decision', async ({ id, roster, arrivals, review }) => {
  render(
    <MemoryRouter initialEntries={[`/activities/${id}`]}>
      <Routes><Route path="/activities/:activityId" element={<RegisterScreen />} /></Routes>
    </MemoryRouter>,
  )
  const summary = within(await screen.findByLabelText('Register summary'))
  for (const [label, count] of [['Roster', roster], ['Checked in', arrivals], ['Awaiting review', review]] as const) {
    expect(summary.getByText(label).nextElementSibling?.firstChild?.textContent).toBe(String(count))
  }
  expect(screen.getByText(/point of observation, not proof of presence in between/)).toBeInTheDocument()
})
