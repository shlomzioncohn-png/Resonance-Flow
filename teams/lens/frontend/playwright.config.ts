import { defineConfig, devices } from '@playwright/test';

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
  fullyParallel: false, // Run tests serially in CI to avoid conflicts
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['html', { open: 'never' }]],
  timeout: 60000, // Increase per-test timeout

  use: {
    baseURL: process.env.PLAYWRIGHT_TEST_BASE_URL || 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    navigationTimeout: 30000,
    actionTimeout: 15000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // TODO: Add Firefox and WebKit once network connectivity is stable
    // For now, testing with Chromium only to avoid timeout issues
  ],

  webServer: process.env.CI
    ? {
        command: 'npm run dev',
        url: 'http://localhost:3000',
        reuseExistingServer: false,
        timeout: 120000,
        stdout: 'ignore',
        stderr: 'ignore',
      }
    : undefined,
});
