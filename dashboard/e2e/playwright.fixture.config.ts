import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

/**
 * Fixture-mode specs: the dashboard runs on in-memory sample data, so no backend, database or models are
 * involved. Chromium only. Run with `npm run e2e:fixture`.
 */
const PORT = Number(process.env.E2E_FIXTURE_PORT ?? 5198)
const ORIGIN = `http://127.0.0.1:${PORT}`

export default defineConfig({
  testDir: import.meta.dirname,
  testMatch: '*.fixture.spec.ts',
  outputDir: path.resolve(import.meta.dirname, '../test-results-fixture'),
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: { baseURL: ORIGIN, timezoneId: 'Asia/Kolkata', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }],
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${PORT} --strictPort`,
    cwd: path.resolve(import.meta.dirname, '..'),
    url: ORIGIN,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_USE_FIXTURES: 'true' },
  },
})
