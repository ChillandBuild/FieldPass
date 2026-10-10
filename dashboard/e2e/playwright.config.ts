import path from 'node:path'
import { defineConfig } from '@playwright/test'
import { BACKEND_URL, DASHBOARD_ORIGIN, DASHBOARD_PORT } from './support/backend'

export default defineConfig({
  testDir: import.meta.dirname,
  testMatch: '*.spec.ts',
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
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${DASHBOARD_PORT} --strictPort`,
    cwd: path.resolve(import.meta.dirname, '..'),
    url: DASHBOARD_ORIGIN,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_API_BASE_URL: BACKEND_URL, VITE_USE_FIXTURES: 'false' },
  },
})
