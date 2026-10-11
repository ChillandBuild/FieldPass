import { expect, test } from '@playwright/test'

async function openRegister(page: import('@playwright/test').Page, name: RegExp): Promise<void> {
  await page.goto('/')
  await page.getByLabel('Username').fill('faculty1')
  await page.getByLabel('Password').fill('anything')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('link', { name: 'Activities' }).click()
  await page.getByRole('link', { name }).click()
  await page.getByText('Asha Verma').first().waitFor()
}

test('faculty open the presence code, see a QR that changes, and close it with Escape', async ({ page }) => {
  await openRegister(page, /Open register for Site Visit/)
  await expect(page.getByText('Code scanned').first()).toBeVisible()

  const opener = page.getByRole('button', { name: 'Show presence code' })
  await opener.click()
  const dialog = page.getByRole('dialog', { name: 'Presence code' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('Scan this with the FieldPass app')).toBeVisible()
  const qr = dialog.getByRole('img', { name: /QR code/ }).locator('svg')
  await expect(qr).toBeVisible()
  const first = await qr.innerHTML()

  // The sample code rolls over every 10 s on the clock; wait until the drawing changes.
  await expect.poll(async () => qr.innerHTML(), { timeout: 15_000 }).not.toBe(first)

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(opener).toBeFocused()
})

test('the overlay explains when the activity is not running and still closes', async ({ page }) => {
  await openRegister(page, /Open register for Lab Safety/)
  await page.getByRole('button', { name: 'Show presence code' }).click()
  await expect(page.getByRole('alert')).toContainText('not running')
  await expect(page.getByRole('img', { name: /QR code/ })).toHaveCount(0)
  await page.getByRole('button', { name: 'Close' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
})
