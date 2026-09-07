import { test as base, Page } from '@playwright/test'

/**
 * Fixture for API mocking in Playwright tests.
 *
 * Provides reusable API interceptors for common scenarios:
 * - mockWorkspaceRecommend() - Mock task recommendation endpoint
 * - mockErrorResponse() - Mock 503/504 errors
 * - mockTimeout() - Simulate request timeout
 */

export interface ApiMockFixtures {
  mockWorkspaceApi: {
    recommendSuccess: (task: any) => Promise<void>
    recommendError: (errorCode: string, statusCode: number) => Promise<void>
    expectRecommendCalled: (withPayload?: any) => Promise<void>
  }
}

export const test = base.extend<ApiMockFixtures>({
  mockWorkspaceApi: async ({ page }, use) => {
    const requestCaptures: { url: string; method: string; body: any }[] = []

    const mockApi = {
      async recommendSuccess(task: any) {
        await page.route('**/api/workspace/recommend', async (route) => {
          // Capture request
          const request = route.request()
          requestCaptures.push({
            url: request.url(),
            method: request.method(),
            body: request.postDataJSON(),
          })

          // Mock success response
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              task,
              explanation: `Task "${task.title}" fits in your available time`,
              fitsInTime: true,
              isHighPriority: task.priority === 'HIGH',
              hasNoDependencies: !task.dependencies || task.dependencies.length === 0,
              confidenceScore: 85,
              alternatives: [],
            }),
          })
        })
      },

      async recommendError(errorCode: string, statusCode: number) {
        await page.route('**/api/workspace/recommend', async (route) => {
          // Capture request
          const request = route.request()
          requestCaptures.push({
            url: request.url(),
            method: request.method(),
            body: request.postDataJSON(),
          })

          // Mock error response
          await route.fulfill({
            status: statusCode,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 'ERROR',
              errorCode,
              message:
                errorCode === 'SERVICE_UNAVAILABLE'
                  ? 'Backend service is currently unavailable'
                  : 'Request timeout - backend took too long to respond',
              path: '/api/workspace/recommend',
              timestamp: new Date().toISOString(),
            }),
          })
        })
      },

      async expectRecommendCalled(withPayload?: any) {
        const call = requestCaptures[requestCaptures.length - 1]
        if (!call) {
          throw new Error('No API call captured for /api/workspace/recommend')
        }
        if (call.method !== 'POST') {
          throw new Error(`Expected POST, got ${call.method}`)
        }
        if (withPayload) {
          Object.entries(withPayload).forEach(([key, value]) => {
            if (call.body[key] !== value) {
              throw new Error(
                `Expected payload.${key}=${value}, got ${call.body[key]}`,
              )
            }
          })
        }
      },
    }

    await use(mockApi)
  },
})

export { expect } from '@playwright/test'
