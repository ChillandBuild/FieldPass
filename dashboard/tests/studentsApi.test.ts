import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

describe('fixture students and phone reset', () => {
  it('lists who has a phone bound', async () => {
    const students = await fixtureApi.listStudents()
    const bound = Object.fromEntries(students.map((s) => [s.username, s.device_bound_at !== null]))
    expect(bound).toEqual({ student1: true, student2: true, student3: true, student4: false })
  })

  it('reset clears the phone and the list shows it', async () => {
    const reset = await fixtureApi.resetDevice('u_s1')
    expect(reset.device_bound_at).toBeNull()
    const again = await fixtureApi.listStudents()
    expect(again.find((s) => s.id === 'u_s1')?.device_bound_at).toBeNull()
  })

  it('reset of an unknown id is not_found', async () => {
    await expect(fixtureApi.resetDevice('u_nobody')).rejects.toMatchObject({ code: 'not_found', status: 404 })
  })

  it('a reset is undone by resetFixtures', async () => {
    await fixtureApi.resetDevice('u_s2')
    resetFixtures()
    await fixtureApi.login('faculty1', 'x')
    expect((await fixtureApi.listStudents()).find((s) => s.id === 'u_s2')?.device_bound_at).not.toBeNull()
  })

  it('students cannot reset phones', async () => {
    await fixtureApi.login('student1', 'x')
    await expect(fixtureApi.resetDevice('u_s1')).rejects.toBeInstanceOf(ApiError)
  })
})
