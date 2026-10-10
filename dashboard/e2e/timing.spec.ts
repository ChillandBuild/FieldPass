/**
 * Time-dependent behaviour, proven against a real second backend that has a short attempt lifetime
 * (ATTEMPT_TTL_SECONDS) and no check-out grace (CHECKOUT_GRACE_MINUTES=0). The clock is never faked:
 * the tests wait in real time for the server clock to pass the expiry or the end of the activity.
 */
import { expect, test, type Page } from '@playwright/test'
import {
  SHORT_ATTEMPT_TTL_SECONDS, SHORT_BACKEND_URL, startShortBackend, stopShortBackend,
} from './support/backend'
import { createStudentApi, type Sample } from './support/studentApi'
import { registerRow, rowCell, signIn } from './support/ui'

const { api, login, enroll, approveAsFaculty, createAttempt, submitAttempt, checkAction } = createStudentApi(SHORT_BACKEND_URL)

const BASE_URL_KEY = 'fieldpass.apiBaseUrl'
const STALE_MESSAGE = 'This student uploaded a new photo. The list has been refreshed.'
const POLL_ALLOWANCE_MS = 20_000 // the register polls every 5 s; allow a few rounds

interface ErrorBody { error: { code: string; message: string } }
interface RecordBody {
  activity_id: string
  status: string | null
  check_in: { id: string; status: string } | null
  check_out: { id: string } | null
  missing_checkout: boolean
  complete: boolean
  next_actions: string[]
}

const s = {
  page: null as unknown as Page,
  faculty: '',
  student1: '',
  student2: '',
  student3: '',
  ids: {} as Record<string, string>,
}

function isoSeconds(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

async function serverTime(): Promise<number> {
  const res = await fetch(`${SHORT_BACKEND_URL}/api/v1/health`)
  return Date.parse(((await res.json()) as { server_time: string }).server_time)
}

async function createActivity(name: string, startsAt: Date, endsAt: Date, usernames: string[]): Promise<string> {
  const res = await api.post<{ id: string }>('/activities', s.faculty, {
    name, venue_name: 'Timing venue', venue_lat: 12.9716, venue_lng: 77.5946, radius_m: 100,
    starts_at: isoSeconds(startsAt), ends_at: isoSeconds(endsAt),
    student_ids: usernames.map((u) => s.ids[u] ?? ''),
  })
  expect(res.status).toBe(201)
  return res.body.id
}

async function enrolApproved(token: string, photo: Sample): Promise<void> {
  const res = await enroll(token, photo)
  expect(res.status).toBe(201)
  await approveAsFaculty(s.faculty, res.body.id)
}

async function recordOf(token: string, activityId: string): Promise<RecordBody> {
  const res = await api.get<{ items: RecordBody[] }>('/attendance/me', token)
  const found = res.body.items.find((r) => r.activity_id === activityId)
  if (!found) throw new Error(`activity ${activityId} missing from /attendance/me`)
  return found
}

async function openRegister(page: Page, activityName: string): Promise<void> {
  await page.getByRole('link', { name: 'Activities', exact: true }).click()
  await page.getByRole('link', { name: `Open register for ${activityName}` }).click()
  await expect(page.getByRole('heading', { name: activityName })).toBeVisible()
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  await startShortBackend()
  s.faculty = await login('faculty1')
  s.student1 = await login('student1')
  s.student2 = await login('student2')
  s.student3 = await login('student3')
  const students = await api.get<{ items: { id: string; username: string }[] }>('/students', s.faculty)
  for (const u of students.body.items) s.ids[u.username] = u.id

  // The dashboard is built for the default backend; the saved "Server address" points this browser at the short one.
  const context = await browser.newContext()
  await context.addInitScript(
    (saved: { key: string; url: string }) => { window.localStorage.setItem(saved.key, saved.url) },
    { key: BASE_URL_KEY, url: SHORT_BACKEND_URL },
  )
  s.page = await context.newPage()
})

test.afterAll(async () => {
  await s.page?.context().close()
  stopShortBackend()
})

test('0. the short backend really runs with a short attempt lifetime', async () => {
  await enrolApproved(s.student1, 'person_a_1')
  await enrolApproved(s.student2, 'person_a_1')
  const activityId = await createActivity('Lifetime probe', new Date(Date.now() - 60_000), new Date(Date.now() + 3600_000), ['student1'])
  const created = await createAttempt(s.student1, activityId, 'check_in')
  expect(created.status).toBe(201)
  expect(Date.parse(created.body.expires_at) - Date.parse(created.body.created_at)).toBe(SHORT_ATTEMPT_TTL_SECONDS * 1000)
})

test('a. an attempt submitted after its lifetime gets 410 attempt_expired and records nothing; a fresh attempt works', async () => {
  const name = 'Expiry activity'
  const activityId = await createActivity(name, new Date(Date.now() - 60_000), new Date(Date.now() + 3 * 3600_000), ['student1', 'student2', 'student3'])
  await signIn(s.page, 'faculty1')
  await openRegister(s.page, name)
  const row = registerRow(s.page, 'Asha Verma')
  await expect(row.locator('.badge')).toHaveText('No check-in yet')

  const created = await createAttempt(s.student1, activityId, 'check_in')
  expect(created.status).toBe(201)
  const expiresAt = Date.parse(created.body.expires_at)

  // Wait (real time) until the SERVER clock is past the attempt's expiry.
  await expect.poll(serverTime, { timeout: 15_000, intervals: [250] }).toBeGreaterThan(expiresAt)

  const late = await submitAttempt(s.student1, created.body.id, { photo: 'person_a_2' })
  expect(late.status).toBe(410)
  const lateBody = late.body as unknown as ErrorBody
  expect(Object.keys(lateBody)).toEqual(['error'])
  expect(lateBody.error.code).toBe('attempt_expired')
  expect(typeof lateBody.error.message).toBe('string')

  // Nothing was recorded: not through the API, not in the register after it has had time to poll.
  const mine = await recordOf(s.student1, activityId)
  expect(mine.check_in).toBeNull()
  expect(mine.status).toBeNull()
  const register = await api.get<{ rows: (RecordBody & { student: { username: string } })[] }>(`/activities/${activityId}/register`, s.faculty)
  expect(register.body.rows.find((r) => r.student.username === 'student1')?.check_in).toBeNull()
  await s.page.waitForResponse((r) => r.url().includes(`/activities/${activityId}/register`) && r.ok(), { timeout: POLL_ALLOWANCE_MS })
  await expect(row.locator('.badge')).toHaveText('No check-in yet')
  await expect(rowCell(row, 'Arrival')).toContainText('None recorded')

  // Retrying the expired attempt stays refused; a fresh attempt succeeds and shows as Recorded.
  expect((await submitAttempt(s.student1, created.body.id, { photo: 'person_a_2' })).status).toBe(410)
  const fresh = await checkAction(s.student1, activityId, 'check_in', { photo: 'person_a_2' })
  expect(fresh.event.status).toBe('recorded')
  await expect(row.locator('.badge')).toHaveText('Recorded', { timeout: POLL_ALLOWANCE_MS })
  await expect(rowCell(row, 'Arrival')).not.toContainText('None recorded')
  await expect(rowCell(row, 'Arrival').locator('.event')).toHaveCount(1)
})

test('b. a check-in with no check-out shows "Missing checkout: incomplete" by polling once the end has passed', async () => {
  const name = 'Missing checkout activity'
  const endsAt = new Date(Date.now() + 25_000)
  const activityId = await createActivity(name, new Date(Date.now() - 60_000), endsAt, ['student2'])
  const done = await checkAction(s.student2, activityId, 'check_in', { photo: 'person_a_2' })
  expect(done.event.status).toBe('recorded')

  await openRegister(s.page, name)
  const row = registerRow(s.page, 'Rohan Iyer')
  await expect(row.locator('.badge')).toHaveText('Recorded')
  // Still before the end: no marker yet (fail loudly if the machine was too slow to get here).
  expect(await serverTime()).toBeLessThan(endsAt.getTime())
  await expect(row.locator('.marker-warn')).toHaveCount(0)
  expect((await recordOf(s.student2, activityId)).missing_checkout).toBe(false)
  await s.page.evaluate(() => { (window as unknown as { __noReload: boolean }).__noReload = true })

  // Real wait for the end of the activity (grace is zero on this backend), then the normal 5 s poll picks it up.
  await expect.poll(serverTime, { timeout: 40_000, intervals: [500] }).toBeGreaterThan(endsAt.getTime())
  await expect(row.locator('.marker-warn')).toHaveText('Missing checkout: incomplete', { timeout: POLL_ALLOWANCE_MS })
  expect(await s.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)

  // No duration or hours anywhere in the row, and the departure is simply absent.
  const text = (await row.innerText()).replace(/\s+/g, ' ')
  expect(text).not.toMatch(/\b(hours?|hrs?|minutes?|mins?|durations?)\b/i)
  expect(text).not.toMatch(/\d\s?(h|hr|hrs|m|min|mins)\b/i)
  await expect(rowCell(row, 'Departure')).toContainText('None recorded')
  await expect(row.locator('.badge')).toHaveText('Recorded')

  const reg = await api.get<{ rows: (RecordBody & { student: { username: string } })[] }>(`/activities/${activityId}/register`, s.faculty)
  const asRow = reg.body.rows.find((r) => r.student.username === 'student2')
  expect(asRow?.missing_checkout).toBe(true)
  expect(asRow?.complete).toBe(false)
  expect(asRow?.check_out).toBeNull()

  const mine = await recordOf(s.student2, activityId)
  expect(mine.missing_checkout).toBe(true)
  expect(mine.complete).toBe(false)
  expect(mine.next_actions).toEqual([])
  const late = await createAttempt(s.student2, activityId, 'check_out')
  expect(late.status).toBe(409)
  expect((late.body as unknown as ErrorBody).error.code).toBe('outside_activity_window')
})

test('c. approving a photo the student has since replaced shows the refresh message and keeps the student pending', async () => {
  const first = await enroll(s.student3, 'person_b')
  expect(first.status).toBe(201)

  await s.page.getByRole('link', { name: 'Enrollments', exact: true }).click()
  await s.page.getByRole('button', { name: 'Refresh' }).click()
  const cards = s.page.locator('li.enroll-card')
  await expect(cards).toHaveCount(1)
  const stale = cards.filter({ has: s.page.getByRole('heading', { name: 'Fatima Khan' }) })
  await expect(stale).toBeVisible()
  await expect(stale.locator('img.enroll-photo')).toBeVisible()

  // The student uploads a replacement behind the faculty member's back (new enrollment id).
  const second = await enroll(s.student3, 'person_a_1')
  expect(second.status).toBe(201)
  expect(second.body.id).not.toBe(first.body.id)

  const approveResponse = s.page.waitForResponse((r) => r.url().includes(`/enrollments/${first.body.id}/approve`))
  const newPhotoRequest = s.page.waitForRequest((r) => r.url().includes(`/enrollments/${second.body.id}/photo`))
  await stale.getByLabel("I have confirmed this person's identity").check()
  await stale.getByRole('button', { name: 'Approve photo' }).click()

  expect((await approveResponse).status()).toBe(404)
  await newPhotoRequest // the reloaded list asked for the NEW enrollment's photo
  await expect(s.page.getByText(STALE_MESSAGE)).toBeVisible()
  await expect(s.page.getByText('Approved the enrollment photo')).toHaveCount(0)
  await expect(cards).toHaveCount(1)
  await expect(cards.first().getByRole('heading', { name: 'Fatima Khan' })).toBeVisible()
  await expect(cards.first().locator('img.enroll-photo')).toBeVisible()

  const status = await api.get<{ id: string; status: string; decided_by: string | null }>('/enrollment/me', s.student3)
  expect(status.body.status).toBe('pending')
  expect(status.body.id).toBe(second.body.id)
  expect(status.body.decided_by).toBeNull()
  const pending = await api.get<{ items: { id: string }[] }>('/enrollments?status=pending', s.faculty)
  expect(pending.body.items.map((e) => e.id)).toEqual([second.body.id])
})
