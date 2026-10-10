import { expect, type Locator, type Page } from '@playwright/test'
import { passwordFor } from './backend'

export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(passwordFor(username))
  await page.getByRole('button', { name: 'Sign in' }).click()
}

export function registerRow(page: Page, studentName: string): Locator {
  return page.locator('li.reg-row').filter({ has: page.getByRole('heading', { name: studentName }) })
}

export function rowCell(row: Locator, label: string): Locator {
  return row.locator('.cell').filter({ has: row.page().locator('.cell-label', { hasText: label }) })
}

/** Local wall-clock text for a datetime-local input, in the test browser's timezone. */
export function localInputValue(date: Date, timeZone = 'Asia/Kolkata'): string {
  const text = new Intl.DateTimeFormat('sv-SE', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
  return text.replace(' ', 'T')
}

export async function expectImageLoaded(image: Locator): Promise<void> {
  await expect.poll(
    async () => image.evaluate((el) => (el as HTMLImageElement).complete ? (el as HTMLImageElement).naturalWidth : 0),
  ).toBeGreaterThan(0)
  const size = await image.evaluate((el) => ({ w: (el as HTMLImageElement).naturalWidth, h: (el as HTMLImageElement).naturalHeight }))
  expect(size.h).toBeGreaterThan(0)
}
