import { expect, test, type Page } from '@playwright/test'
import { api, approveAsFaculty, checkAction, enroll, login, submitAttempt, VENUE } from './support/studentApi'
import { expectImageLoaded, localInputValue, registerRow, rowCell, signIn } from './support/ui'

interface RecordBody {
  status: string | null
  check_in: { id: string } | null
  checkpoints: { id: string }[]
  check_out: { id: string } | null
  decision: { decision: string; reason: string; is_current: boolean } | null
  decision_history: { decision: string; reason: string; is_current: boolean }[]
  observations: { note: string }[]
}

const FAR = { lat: VENUE.lat + 0.01, lng: VENUE.lng } // about 1.1 km north of the venue

const s = {
  page: null as unknown as Page,
  faculty: '',
  student1: '',
  student2: '',
  student3: '',
  activityId: '',
  checkInEventId: '',
  checkInAttemptId: '',
}

async function myRecord(token: string): Promise<RecordBody> {
  const res = await api.get<{ items: (RecordBody & { activity_id: string })[] }>('/attendance/me', token)
  const found = res.body.items.find((r) => r.activity_id === s.activityId)
  if (!found) throw new Error('activity missing from /attendance/me')
  return found
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  s.page = await browser.newPage()
  s.faculty = await login('faculty1')
  s.student1 = await login('student1')
  s.student2 = await login('student2')
  s.student3 = await login('student3')
})

test.afterAll(async () => {
  await s.page.close()
})

test('1. faculty logs in through the UI; a student account is refused', async ({ browser }) => {
  const studentPage = await browser.newPage()
  await signIn(studentPage, 'student1')
  await expect(studentPage.getByText('This dashboard is for faculty')).toBeVisible()
  await expect(studentPage.getByRole('heading', { name: 'FieldPass faculty sign in' })).toBeVisible()
  expect(await studentPage.evaluate(() => sessionStorage.getItem('fieldpass.token'))).toBeNull()
  await studentPage.close()

  await signIn(s.page, 'faculty1')
  await expect(s.page.getByRole('heading', { name: /Enrollment photos waiting/ })).toBeVisible()
  await expect(s.page.getByText('Dr. Meera Rao')).toBeVisible()
})

test('2. enrollment photo shows in the pending list, is rendered, and faculty approves', async () => {
  const res = await enroll(s.student1, 'person_a_1')
  expect(res.status).toBe(201)
  expect(res.body.status).toBe('pending')

  await s.page.getByRole('button', { name: 'Refresh' }).click()
  const card = s.page.locator('li.enroll-card').filter({ has: s.page.getByRole('heading', { name: 'Asha Verma' }) })
  await expect(card).toBeVisible()
  await expectImageLoaded(card.locator('img.enroll-photo'))

  await card.getByLabel("I have confirmed this person's identity").check()
  await card.getByRole('button', { name: 'Approve photo' }).click()
  await expect(s.page.getByText('Approved the enrollment photo for Asha Verma.')).toBeVisible()

  const me = await api.get<{ status: string; decided_by: string }>('/enrollment/me', s.student1)
  expect(me.body.status).toBe('approved')
  expect(me.body.decided_by).toBe('Dr. Meera Rao')

  // The other two students enrol with a different face each and are approved through the API.
  for (const [token, photo] of [[s.student2, 'person_b'], [s.student3, 'person_a_1']] as const) {
    const e = await enroll(token, photo)
    expect(e.status).toBe(201)
    await approveAsFaculty(s.faculty, e.body.id)
  }
})

test('3. faculty creates an activity through the form and sees the boundary', async () => {
  await s.page.getByRole('link', { name: 'Activities' }).click()
  await s.page.getByRole('link', { name: 'New activity' }).click()
  await s.page.getByLabel('Activity name').fill('E2E site visit')
  await s.page.getByLabel('Venue name').fill('E2E plant gate')
  await s.page.getByLabel(/^Starts/).fill(localInputValue(new Date(Date.now() - 60_000)))
  await s.page.getByLabel(/^Ends/).fill(localInputValue(new Date(Date.now() + 3 * 3600_000)))
  await s.page.getByLabel('Venue latitude').fill(String(VENUE.lat))
  await s.page.getByLabel('Venue longitude').fill(String(VENUE.lng))
  await s.page.getByLabel('Radius (metres)').fill('100')
  for (const name of ['Asha Verma', 'Rohan Iyer', 'Fatima Khan']) await s.page.getByLabel(new RegExp(name)).check()
  await s.page.getByRole('button', { name: 'Create activity' }).click()

  await expect(s.page.getByRole('heading', { name: 'Activity created' })).toBeVisible()
  const boundary = s.page.getByRole('region', { name: 'Venue boundary' })
  await expect(boundary).toContainText(String(VENUE.lat))
  await expect(boundary).toContainText(String(VENUE.lng))
  await expect(boundary).toContainText('100 metres')

  const href = await s.page.getByRole('link', { name: 'Open register' }).getAttribute('href')
  s.activityId = href?.split('/').pop() ?? ''
  expect(s.activityId).toMatch(/^act_/)
  const created = await api.get<{ roster_count: number }>(`/activities/${s.activityId}`, s.faculty)
  expect(created.body.roster_count).toBe(3)
})

test('4. check-in appears in the register by polling, without a reload', async () => {
  await s.page.getByRole('link', { name: 'Open register' }).click()
  const row = registerRow(s.page, 'Asha Verma')
  await expect(row).toContainText('No check-in yet')
  await s.page.evaluate(() => { (window as unknown as { __noReload: boolean }).__noReload = true })

  const done = await checkAction(s.student1, s.activityId, 'check_in', { photo: 'person_a_2' })
  s.checkInEventId = done.event.id
  s.checkInAttemptId = done.attemptId
  expect(done.event.status).toBe('recorded')

  await expect(row.locator('.badge')).toHaveText('Recorded', { timeout: 20_000 })
  await expect(rowCell(row, 'Arrival')).not.toContainText('None recorded')
  await expect(rowCell(row, 'Arrival')).toContainText('Face comparison matched')
  expect(await s.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
})

test('5. faculty opens a checkpoint window; the checkpoint shows in the register', async () => {
  await s.page.getByRole('button', { name: 'Open checkpoint window' }).click()
  await expect(s.page.getByText('Students can submit one checkpoint during this window.')).toBeVisible()

  await checkAction(s.student1, s.activityId, 'checkpoint', { photo: 'person_a_2' })
  const row = registerRow(s.page, 'Asha Verma')
  const cell = rowCell(row, 'Last recorded checkpoint')
  await expect(cell).not.toContainText('None recorded', { timeout: 20_000 })
  await expect(cell).toContainText('Face comparison matched')
})

test('6. check-out, then faculty confirms with a reason', async () => {
  await checkAction(s.student1, s.activityId, 'check_out', { photo: 'person_a_2' })
  const row = registerRow(s.page, 'Asha Verma')
  await expect(rowCell(row, 'Departure')).not.toContainText('None recorded', { timeout: 20_000 })

  await row.getByRole('button', { name: /Confirm or decline attendance/ }).click()
  await row.getByLabel('Reason (required)').fill('Seen on site all morning')
  await row.getByRole('button', { name: 'Confirm attendance' }).click()

  await expect(row.locator('.badge')).toHaveText('Confirmed by faculty')
  const decision = row.locator('.decision')
  await expect(decision).toContainText('Reason: Seen on site all morning')
  await expect(decision).toContainText('by Dr. Meera Rao')
  await expect(decision).toContainText(/\d{2}:\d{2}/)

  expect((await myRecord(s.student1)).status).toBe('confirmed')
})

test('7a. a different person\'s face leads to Needs review with a face reason', async () => {
  const done = await checkAction(s.student2, s.activityId, 'check_in', { photo: 'person_a_1' })
  test.info().annotations.push({ type: 'real engine verdict, different person', description: done.event.face.verdict })
  expect(['no_match', 'uncertain']).toContain(done.event.face.verdict)
  expect(done.event.status).toBe('needs_review')

  const row = registerRow(s.page, 'Rohan Iyer')
  await expect(row.locator('.badge')).toHaveText('Needs review', { timeout: 20_000 })
  await expect(row.getByRole('list', { name: /Review reasons/ })).toContainText(/Face comparison (did not match|uncertain)/)
})

test('7b. the right face far outside the boundary leads to Needs review with a location reason', async () => {
  const done = await checkAction(s.student3, s.activityId, 'check_in', { photo: 'person_a_2', ...FAR })
  test.info().annotations.push({ type: 'real engine verdict, same person', description: done.event.face.verdict })
  expect(done.event.face.verdict).toBe('match')
  expect(done.event.location.verdict).toBe('outside')
  expect(done.event.status).toBe('needs_review')

  const row = registerRow(s.page, 'Fatima Khan')
  await expect(row.locator('.badge')).toHaveText('Needs review', { timeout: 20_000 })
  await expect(row.getByRole('list', { name: /Review reasons/ })).toContainText('Location outside the boundary')
})

test('8. replaying the same submit returns the same event; one arrival in the register', async () => {
  const replay = await submitAttempt(s.student1, s.checkInAttemptId, { photo: 'person_a_2' })
  expect(replay.status).toBe(200)
  expect(replay.body.id).toBe(s.checkInEventId)

  const register = await api.get<{ rows: (RecordBody & { student: { username: string } })[] }>(
    `/activities/${s.activityId}/register`, s.faculty)
  const asha = register.body.rows.find((r) => r.student.username === 'student1')
  expect(asha?.check_in?.id).toBe(s.checkInEventId)
  expect(asha?.checkpoints).toHaveLength(1)

  const row = registerRow(s.page, 'Asha Verma')
  await expect(rowCell(row, 'Arrival').locator('.event')).toHaveCount(1)
  await expect(s.page.locator('li.reg-row')).toHaveCount(3)
})

test('9. an in-person observation shows separately from automated observations', async () => {
  const row = registerRow(s.page, 'Fatima Khan')
  await row.getByRole('button', { name: /Record in-person observation/ }).click()
  await row.getByLabel('What you saw in person').selectOption('present')
  await row.getByLabel('Note (required)').fill('Signed the paper sheet')
  await row.getByRole('button', { name: 'Save observation' }).click()

  const box = row.locator('.faculty-obs')
  await expect(box).toContainText('In-person observations by faculty')
  await expect(box).toContainText('Signed the paper sheet')
  await expect(box).toContainText('Dr. Meera Rao')
  await expect(rowCell(row, 'Arrival')).not.toContainText('Signed the paper sheet')
  await expect(row.locator('.badge')).toHaveText('Needs review') // automated status is unchanged
})

test('10. a decline followed by a confirm keeps both decisions; the newest is shown', async () => {
  const row = registerRow(s.page, 'Rohan Iyer')
  await row.getByRole('button', { name: /Confirm or decline attendance/ }).click()
  await row.getByLabel('Reason (required)').fill('Selfie did not look like the enrolled photo')
  await row.getByRole('button', { name: 'Decline attendance' }).click()
  await expect(row.locator('.badge')).toHaveText('Not confirmed by faculty')

  await row.getByRole('button', { name: /Confirm or decline attendance/ }).click()
  await row.getByLabel('Reason (required)').fill('Checked in person, it was Rohan')
  await row.getByRole('button', { name: 'Confirm attendance' }).click()
  await expect(row.locator('.badge')).toHaveText('Confirmed by faculty')
  await expect(row.locator('.decision')).toContainText('Reason: Checked in person, it was Rohan')

  const earlier = row.locator('details.earlier-decisions')
  await expect(earlier).toContainText('Selfie did not look like the enrolled photo')
  await expect(earlier).toContainText('Not confirmed')

  const mine = await myRecord(s.student2)
  expect(mine.status).toBe('confirmed')
  expect(mine.decision_history.map((d) => d.decision)).toEqual(['confirmed', 'not_confirmed'])
  expect(mine.decision_history[0]?.is_current).toBe(true)
})

test('10b. new automated evidence after a decision marks it outdated, history intact', async () => {
  await checkAction(s.student2, s.activityId, 'check_out', { photo: 'person_b' })
  const row = registerRow(s.page, 'Rohan Iyer')
  await expect(row.locator('.decision-outdated')).toContainText('Outdated decision, new evidence arrived', { timeout: 20_000 })
  await expect(row.locator('.decision-outdated')).toContainText('Checked in person, it was Rohan')
  await expect(row.locator('.badge')).toHaveText('Needs review')
  await expect(row.locator('details.earlier-decisions')).toContainText('Selfie did not look like the enrolled photo')
})

test('11. CSV download has a proper name and one line per rostered student', async () => {
  const [download] = await Promise.all([
    s.page.waitForEvent('download'),
    s.page.getByRole('button', { name: 'Download CSV' }).click(),
  ])
  expect(download.suggestedFilename()).toBe(`attendance_${s.activityId}.csv`)
  const path = await download.path()
  const { readFileSync } = await import('node:fs')
  const lines = readFileSync(path, 'utf-8').split('\r\n').filter((l) => l.length > 0)
  expect(lines).toHaveLength(1 + 3) // header + one line per rostered student
  for (const name of ['Asha Verma', 'Rohan Iyer', 'Fatima Khan']) {
    expect(lines.filter((l) => l.includes(name))).toHaveLength(1)
  }
})

test('12. a student token on a faculty endpoint gets 403 forbidden in the error envelope', async () => {
  for (const res of [
    await api.get<{ error: { code: string; message: string } }>(`/activities/${s.activityId}/register`, s.student1),
    await api.get<{ error: { code: string; message: string } }>('/enrollments?status=pending', s.student1),
    await api.post<{ error: { code: string; message: string } }>(
      `/activities/${s.activityId}/checkpoints`, s.student1, { duration_minutes: 5 }),
  ]) {
    expect(res.status).toBe(403)
    expect(res.body.error.code).toBe('forbidden')
    expect(typeof res.body.error.message).toBe('string')
    expect(Object.keys(res.body)).toEqual(['error'])
  }
  const anon = await api.get<{ error: { code: string } }>('/students', '')
  expect(anon.status).toBe(401)
  expect(anon.body.error.code).toBe('not_authenticated')
})

test('screenshots: the real register at 1440 and 320 wide', async ({ browserName }, testInfo) => {
  // Only the default browser refreshes the committed screenshots; Firefox and WebKit write to their test-results folder.
  const shots =
    browserName === 'chromium'
      ? new URL('../screenshots/', import.meta.url).pathname
      : `${testInfo.outputDir}/`
  for (const width of [1440, 320]) {
    await s.page.setViewportSize({ width, height: 900 })
    await expect(s.page.locator('li.reg-row')).toHaveCount(3)
    const overflow = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(0)
    await s.page.screenshot({ path: `${shots}real-register-${width}.png`, fullPage: true })
  }
})
