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
    recommendSuccessWithDelay: (task: any, delayMs: number) => Promise<void>
    recommendError: (errorCode: string, statusCode: number) => Promise<void>
    recommendEmpty: () => Promise<void>
    startTaskSuccess: (taskId: string) => Promise<void>
    expectRecommendCalled: (withPayload?: any) => Promise<void>
  }
}

export const test = base.extend<ApiMockFixtures>({
  mockWorkspaceApi: async ({ page }, use) => {
    const requestCaptures: { url: string; method: string; body: any }[] = []
    let routeHandled = false

    const mockApi = {
      async recommendSuccess(task: any) {
        // Clear previous captures and reset flag
        requestCaptures.length = 0
        routeHandled = false
        
        await page.route('**/api/workspace/recommend', async (route) => {
          // Capture request
          const request = route.request()
          requestCaptures.push({
            url: request.url(),
            method: request.method(),
            body: request.postDataJSON(),
          })
          routeHandled = true

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
                  : errorCode === 'NETWORK_ERROR'
                    ? 'Network error: Could not reach backend service'
                    : 'Request timeout - backend took too long to respond',
              path: '/api/workspace/recommend',
              timestamp: new Date().toISOString(),
            }),
          })
        })
      },

      async recommendSuccessWithDelay(task: any, delayMs: number) {
        await page.route('**/api/workspace/recommend', async (route) => {
          const request = route.request()
          requestCaptures.push({
            url: request.url(),
            method: request.method(),
            body: request.postDataJSON(),
          })

          // Simulate slow backend
          await new Promise((resolve) => setTimeout(resolve, delayMs))

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

      async recommendEmpty() {
        await page.route('**/api/workspace/recommend', async (route) => {
          const request = route.request()
          requestCaptures.push({
            url: request.url(),
            method: request.method(),
            body: request.postDataJSON(),
          })

          // Return 200 but with null task (no suitable task found)
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              task: null,
              explanation: 'No suitable tasks found for your available time',
              fitsInTime: false,
              isHighPriority: false,
              hasNoDependencies: false,
              confidenceScore: 0,
              alternatives: [],
            }),
          })
        })
      },

      async startTaskSuccess(taskId: string) {
        await page.route(`**/api/tasks/${taskId}/status`, async (route) => {
          const request = route.request()
          
          // Mock successful status update
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              id: taskId,
              status: 'IN_PROGRESS',
              updatedAt: new Date().toISOString(),
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
