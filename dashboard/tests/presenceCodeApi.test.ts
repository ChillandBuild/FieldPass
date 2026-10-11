import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { realApi } from '../src/api/realClient'

describe('fixture getPresenceCode', () => {
  beforeEach(async () => {
    resetFixtures()
    await fixtureApi.login('faculty1', 'x')
  })
  afterEach(() => { vi.useRealTimers() })

  it('returns the contract shape for a running activity', async () => {
    const res = await fixtureApi.getPresenceCode('act_1')
    expect(res.code).toMatch(/^fp1\.\d+\.[A-Za-z0-9_-]+$/)
    expect(res.step_seconds).toBe(10)
    const left = Date.parse(res.expires_at) - Date.parse(res.server_time)
    expect(left).toBeGreaterThan(0)
    expect(left).toBeLessThanOrEqual(10_000)
  })

  it('is deterministic inside one step and changes in the next', async () => {
    // Stay inside the running activity: start 1 s into a 10 s step of the real "now".
    const stepStart = Math.floor(Date.now() / 10_000) * 10_000
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(stepStart + 1000)
    const a = await fixtureApi.getPresenceCode('act_1')
    vi.setSystemTime(stepStart + 8000)
    const b = await fixtureApi.getPresenceCode('act_1')
    expect(b.code).toBe(a.code)
    expect(b.expires_at).toBe(a.expires_at)
    vi.setSystemTime(Date.parse(a.expires_at))
    const c = await fixtureApi.getPresenceCode('act_1')
    expect(c.code).not.toBe(a.code)
  })

  it('differs per activity', async () => {
    const a = await fixtureApi.getPresenceCode('act_1')
    resetFixtures()
    await fixtureApi.login('faculty1', 'x')
    const created = await fixtureApi.createActivity({
      name: 'X', venue_name: 'V', venue_lat: 1, venue_lng: 1, radius_m: 50,
      starts_at: new Date(Date.now() - 60_000).toISOString(), ends_at: new Date(Date.now() + 3_600_000).toISOString(), student_ids: [],
    })
    const b = await fixtureApi.getPresenceCode(created.id)
    expect(b.code).not.toBe(a.code)
  })

  it('is not_found for an unknown activity', async () => {
    await expect(fixtureApi.getPresenceCode('nope')).rejects.toMatchObject({ code: 'not_found', status: 404 })
  })

  it('is outside_activity_window for an activity that is not running (the past one)', async () => {
    await expect(fixtureApi.getPresenceCode('act_2')).rejects.toMatchObject({ code: 'outside_activity_window', status: 409 })
  })

  it('is refused for students', async () => {
    await fixtureApi.login('student1', 'x')
    await expect(fixtureApi.getPresenceCode('act_1')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('real getPresenceCode', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('GETs /activities/{id}/presence-code and returns the body', async () => {
    const body = { code: 'fp1.176000000.AbCd', expires_at: '2026-06-01T10:00:10Z', server_time: '2026-06-01T10:00:03Z', step_seconds: 10 }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(realApi.getPresenceCode('act 1')).resolves.toEqual(body)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toMatch(/\/api\/v1\/activities\/act%201\/presence-code$/)
    expect(init.method).toBe('GET')
  })

  it('maps an error envelope to ApiError', async () => {
    const env = { error: { code: 'outside_activity_window', message: 'closed' } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(env), { status: 409 })))
    await expect(realApi.getPresenceCode('a')).rejects.toMatchObject({ code: 'outside_activity_window', status: 409 })
  })
})
