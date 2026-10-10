// Usage: node scripts/screenshots.mjs [baseUrl]   (app must be running with VITE_USE_FIXTURES=true)
import { chromium } from 'playwright'

const base = process.argv[2] ?? 'http://localhost:5199'
const widths = [320, 768, 1024, 1440]
const out = new URL('../screenshots/', import.meta.url).pathname
const results = []

const browser = await chromium.launch()
for (const width of widths) {
  const ctx = await browser.newContext({ viewport: { width, height: 900 } })
  const page = await ctx.newPage()
  const shot = async (name) => {
    await page.screenshot({ path: `${out}${name}-${width}.png`, fullPage: true })
    const m = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }))
    // Element-level check too, since body overflow-x: hidden could mask a page-level overflow.
    const wide = await page.evaluate(() => {
      const cw = document.documentElement.clientWidth
      return [...document.body.querySelectorAll('*')]
        .filter((el) => el.getBoundingClientRect().right > cw + 1 && getComputedStyle(el).position !== 'fixed')
        .slice(0, 3).map((el) => `${el.tagName}.${el.className}`)
    })
    results.push({ name, width, scrollWidth: m.sw, clientWidth: m.cw, overflow: m.sw > m.cw || wide.length > 0, wide: wide.join(' | ') })
  }

  await page.goto(`${base}/`)
  await page.getByLabel('Username').waitFor()
  await shot('login')

  await page.getByLabel('Username').fill('faculty1')
  await page.getByLabel('Password').fill('anything')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('heading', { name: /Enrollment photos waiting/ }).waitFor()
  await page.locator('img.enroll-photo').first().waitFor()
  await shot('enrollments')

  await page.getByRole('link', { name: 'Activities' }).click()
  await page.getByRole('link', { name: 'New activity' }).click()
  await page.getByRole('heading', { name: 'New activity' }).waitFor()
  await page.getByText('Asha Verma').waitFor()
  await shot('activity-create')

  await page.getByRole('link', { name: 'Cancel' }).click()
  await page.getByRole('link', { name: /Open register for Site Visit/ }).click()
  await page.getByRole('heading', { name: 'Site Visit: Water Treatment Plant' }).waitFor()
  await page.getByText('Asha Verma').first().waitFor()
  await shot('register')

  await page.getByRole('link', { name: 'All activities' }).click()
  await page.getByRole('link', { name: /Open register for Lab Safety/ }).click()
  await page.getByRole('heading', { name: 'Lab Safety Walkthrough' }).waitFor()
  await page.getByText('Missing checkout: incomplete').waitFor()
  await shot('register-past')

  await page.getByRole('button', { name: /Confirm or decline attendance for Rohan/ }).click()
  await page.getByRole('button', { name: 'Confirm attendance' }).click()
  await page.getByText('Enter a reason for your decision.').waitFor()
  await shot('register-decision-error')

  await page.goto(`${base}/about`)
  await page.getByRole('heading', { name: /does and does not prove/ }).waitFor()
  await shot('about')
  await ctx.close()
}
await browser.close()

console.table(results)
const bad = results.filter((r) => r.overflow)
if (bad.length > 0) {
  console.error('HORIZONTAL OVERFLOW:', JSON.stringify(bad))
  process.exit(1)
}
console.log('No horizontal overflow at any width.')
