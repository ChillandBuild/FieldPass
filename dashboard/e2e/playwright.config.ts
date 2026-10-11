import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'
import { BACKEND_URL, DASHBOARD_ORIGIN, DASHBOARD_PORT } from './support/backend'

/**
 * One browser per run. The specs are serial and share a freshly seeded backend, so each browser gets its own
 * `npm run e2e` (its own backend and data) instead of several projects re-using one database.
 * Select with E2E_BROWSER=chromium|firefox|webkit (default chromium); `npm run e2e:browsers` runs all three.
 */
const BROWSERS = {
  chromium: devices['Desktop Chrome'],
  firefox: devices['Desktop Firefox'],
  webkit: devices['Desktop Safari'],
} as const
type BrowserName = keyof typeof BROWSERS

function selectedBrowser(): BrowserName {
  const name = process.env.E2E_BROWSER ?? 'chromium'
  if (name in BROWSERS) return name as BrowserName
  throw new Error(`E2E_BROWSER must be one of ${Object.keys(BROWSERS).join(', ')} (got "${name}")`)
}

const browserName = selectedBrowser()

export default defineConfig({
  testDir: import.meta.dirname,
  testMatch: '*.spec.ts',
  testIgnore: '*.fixture.spec.ts',
  outputDir: path.resolve(import.meta.dirname, '../test-results'),
  globalSetup: './support/globalSetup.ts',
  globalTeardown: './support/globalTeardown.ts',
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: DASHBOARD_ORIGIN,
    timezoneId: 'Asia/Kolkata',
    trace: 'retain-on-failure',
  },
  projects: [{ name: browserName, use: BROWSERS[browserName] }],
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${DASHBOARD_PORT} --strictPort`,
    cwd: path.resolve(import.meta.dirname, '..'),
    url: DASHBOARD_ORIGIN,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_API_BASE_URL: BACKEND_URL, VITE_USE_FIXTURES: 'false' },
  },
})
