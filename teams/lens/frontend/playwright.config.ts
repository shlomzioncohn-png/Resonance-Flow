import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration for LENS Frontend E2E tests.
 *
 * Tests verify both VISIBLE (UI) and INVISIBLE (API) behavior:
 * - VISIBLE: UI elements render, user interactions work
 * - INVISIBLE: API calls are made with correct payloads, responses are processed
 *
 * Strategy:
 * 1. Use page.route() to intercept API calls
 * 2. Verify request payload (availableTime, filter, etc.)
 * 3. Mock response from Gateway/BFF
 * 4. Assert UI updated with mocked data
 */

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',

  use: {
    baseURL: process.env.PLAYWRIGHT_TEST_BASE_URL || 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
})
