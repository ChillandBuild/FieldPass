/**
 * Rotating presence code, end to end against the real backend: the dashboard shows the code, a simulated phone
 * scans it, and the scan settles an "uncertain" location reading (and nothing else). The control student submits
 * the same uncertain reading without a scan and is sent to review.
 */
import { expect, test, type Page } from '@playwright/test'
import { api, createAttempt, enroll, approveAsFaculty, login, scanPresence, submitAttempt, VENUE } from './support/studentApi'
import type { Sample } from './support/studentApi'
import { registerRow, rowCell, signIn } from './support/ui'

const ACTIVITY_NAME = 'E2E presence code visit'
const RADIUS_M = 100
/** Larger than the radius, so a reading at the venue centre is "uncertain": distance - accuracy <= radius < distance + accuracy. */
const UNCERTAIN_ACCURACY_M = 150
const POLL_ALLOWANCE_MS = 20_000 // the register polls every 5 s; allow a few rounds

interface PresenceCodeBody {
  code: string
  expires_at: string
  server_time: string
  step_seconds: number
}

const s = {
  page: null as unknown as Page,
  faculty: '',
  student1: '',
  student2: '',
  activityId: '',
  shownCode: '',
  uniqueSubmits: 0,
}

/** A spot about 1 m from the venue centre, different on every call so no submit is flagged as a repeated location. */
function nearVenue(): { lat: number; lng: number } {
  s.uniqueSubmits += 1
  return { lat: VENUE.lat + 0.00001 + ((Date.now() % 50_000) + s.uniqueSubmits) * 1e-8, lng: VENUE.lng }
}

async function ensureApprovedEnrollment(token: string, photo: Sample): Promise<void> {
  const mine = await api.get<{ status?: string }>('/enrollment/me', token)
  if (mine.status === 200 && mine.body.status === 'approved') return
  const created = await enroll(token, photo)
  expect(created.status).toBe(201)
  await approveAsFaculty(s.faculty, created.body.id)
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  s.faculty = await login('faculty1')
  s.student1 = await login('student1')
  s.student2 = await login('student2')
  // Same photos as fieldpass.spec.ts, so the enrolments are reused when that spec ran first.
  await ensureApprovedEnrollment(s.student1, 'person_a_1')
  await ensureApprovedEnrollment(s.student2, 'person_b')

  const students = await api.get<{ items: { id: string; username: string }[] }>('/students', s.faculty)
  const ids = students.body.items.filter((u) => ['student1', 'student2'].includes(u.username)).map((u) => u.id)
  expect(ids).toHaveLength(2)
  const created = await api.post<{ id: string }>('/activities', s.faculty, {
    name: ACTIVITY_NAME, venue_name: 'E2E plant gate', venue_lat: VENUE.lat, venue_lng: VENUE.lng, radius_m: RADIUS_M,
    starts_at: new Date(Date.now() - 60_000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    ends_at: new Date(Date.now() + 3 * 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    student_ids: ids,
  })
  expect(created.status).toBe(201)
  s.activityId = created.body.id

  s.page = await browser.newPage()
})

test.afterAll(async () => {
  await s.page?.close()
})

test('1. faculty opens the register and the presence code overlay shows a QR from the code the dashboard fetched', async () => {
  await signIn(s.page, 'faculty1')
  await s.page.getByRole('link', { name: 'Activities', exact: true }).click()
  await s.page.getByRole('link', { name: `Open register for ${ACTIVITY_NAME}` }).click()
  await expect(s.page.getByRole('heading', { name: ACTIVITY_NAME })).toBeVisible()
  await expect(s.page.locator('li.reg-row')).toHaveCount(2)
  await s.page.evaluate(() => { (window as unknown as { __noReload: boolean }).__noReload = true })

  // The code is read from the dashboard's own request, so this proves the page fetched it.
  const fetched = s.page.waitForResponse((res) =>
    res.url().includes(`/api/v1/activities/${s.activityId}/presence-code`) && res.request().method() === 'GET')
  await s.page.getByRole('button', { name: 'Show presence code' }).click()
  const response = await fetched
  expect(response.status()).toBe(200)
  const body = (await response.json()) as PresenceCodeBody
  expect(body.code).toMatch(/^fp1\.\d+\.[A-Za-z0-9_-]+$/)
  expect(body.step_seconds).toBeGreaterThan(0)
  expect(Date.parse(body.expires_at)).toBeGreaterThan(Date.parse(body.server_time))
  s.shownCode = body.code

  const dialog = s.page.getByRole('dialog', { name: 'Presence code' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('Scan this with the FieldPass app')).toBeVisible()
  await expect(dialog.getByRole('img', { name: /QR code/ }).locator('svg')).toBeVisible()
  await expect(dialog.getByTestId('presence-countdown')).toBeVisible()

  await s.page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
})

test('2. a garbage code gets 200 and verified false', async () => {
  const attempt = await createAttempt(s.student1, s.activityId, 'check_in')
  expect(attempt.status).toBe(201)
  const garbage = await scanPresence(s.student1, attempt.body.id, 'not-a-real-code')
  expect(garbage.status).toBe(200)
  expect(garbage.body).toEqual({ verified: false })
  const wrongShape = await scanPresence(s.student1, attempt.body.id, 'fp1.1.AAAAAAAAAAAAAAAAAAAAAA')
  expect(wrongShape.status).toBe(200)
  expect(wrongShape.body).toEqual({ verified: false })
})

test('3. a student who scans the dashboard code and is "uncertain" on location is Recorded without a location reason', async () => {
  const attempt = await createAttempt(s.student1, s.activityId, 'check_in')
  expect(attempt.status).toBe(201)

  const scan = await scanPresence(s.student1, attempt.body.id, s.shownCode)
  expect(scan.status).toBe(200)
  expect(scan.body).toEqual({ verified: true })

  const submitted = await submitAttempt(s.student1, attempt.body.id, { photo: 'person_a_2', ...nearVenue(), accuracyM: UNCERTAIN_ACCURACY_M })
  expect(submitted.status).toBe(200)
  expect(submitted.body.location.verdict).toBe('uncertain') // the stored verdict stays as measured
  expect(submitted.body.face.verdict).toBe('match')
  expect(submitted.body.presence_verified).toBe(true)
  expect(submitted.body.review_reasons).not.toContain('location_uncertain')
  expect(submitted.body.review_reasons).toEqual([])
  expect(submitted.body.status).toBe('recorded')
})

test('4. the control student with the same uncertain reading and no scan needs review for location_uncertain', async () => {
  const attempt = await createAttempt(s.student2, s.activityId, 'check_in')
  expect(attempt.status).toBe(201)

  const submitted = await submitAttempt(s.student2, attempt.body.id, { photo: 'person_b', ...nearVenue(), accuracyM: UNCERTAIN_ACCURACY_M })
  expect(submitted.status).toBe(200)
  expect(submitted.body.location.verdict).toBe('uncertain')
  expect(submitted.body.presence_verified).toBe(false)
  expect(submitted.body.review_reasons).toContain('location_uncertain')
  expect(submitted.body.status).toBe('needs_review')
})

test('5. the register shows "Code scanned" on the scanning student only, by polling and without a reload', async () => {
  const scanned = registerRow(s.page, 'Asha Verma')
  const control = registerRow(s.page, 'Rohan Iyer')

  await expect(scanned.locator('.badge')).toHaveText('Recorded', { timeout: POLL_ALLOWANCE_MS })
  await expect(rowCell(scanned, 'Arrival').getByText('Code scanned')).toBeVisible({ timeout: POLL_ALLOWANCE_MS })

  await expect(control.locator('.badge')).toHaveText('Needs review', { timeout: POLL_ALLOWANCE_MS })
  await expect(rowCell(control, 'Arrival')).not.toContainText('None recorded')
  await expect(rowCell(control, 'Arrival').getByText('Code scanned')).toHaveCount(0)
  await expect(s.page.getByText('Code scanned')).toHaveCount(1)

  expect(await s.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
})

test('6. the faculty register API reports presence_verified per event', async () => {
  const register = await api.get<{ rows: { student: { username: string }; check_in: { presence_verified: boolean; status: string } | null }[] }>(
    `/activities/${s.activityId}/register`, s.faculty)
  expect(register.status).toBe(200)
  const byUser = new Map(register.body.rows.map((r) => [r.student.username, r.check_in]))
  expect(byUser.get('student1')?.presence_verified).toBe(true)
  expect(byUser.get('student1')?.status).toBe('recorded')
  expect(byUser.get('student2')?.presence_verified).toBe(false)
  expect(byUser.get('student2')?.status).toBe('needs_review')
})
