import { test, expect } from '../fixtures/api-mocking'

/**
 * E2E Tests for Workspace Task Recommendation Screen
 *
 * Tests verify BOTH VISIBLE (UI) and INVISIBLE (API) behavior:
 * - VISIBLE: User sees form, clicks button, sees result
 * - INVISIBLE: Frontend sends correct payload, handles responses properly
 *
 * Each test scenario:
 * 1. Mock API endpoint with page.route()
 * 2. User interacts with UI
 * 3. Verify API was called correctly (INVISIBLE)
 * 4. Verify UI updated correctly (VISIBLE)
 */

test.describe('Workspace - Task Recommendation', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to workspace page (or wherever recommendation UI is)
    // Replace with actual route once pages are built
    await page.goto('/workspace') // TODO: Confirm actual route
  })

  // ═══════════════════════════════════════════════════════════════════
  // SCENARIO 1: Happy Path - User gets recommendation
  // ═══════════════════════════════════════════════════════════════════

  test('Happy Path: Shows recommendation when API returns success', async ({
    page,
    mockWorkspaceApi,
  }) => {
    // Mock task from BFF
    const mockTask = {
      id: 'task_001',
      title: 'Review API changes',
      description: 'Review the latest API changes and update integration tests.',
      status: 'READY',
      priority: 'HIGH',
      effortEstimate: 35,
      dueDate: '2026-08-30T17:00:00Z',
      owner: 'Alex Morgan',
      project: 'Platform Service',
      dependencies: [],
      constraints: [],
    }

    // Setup: Mock API to return success
    await mockWorkspaceApi.recommendSuccess(mockTask)

    // VISIBLE: User fills form
    await page.fill('[data-testid="available-time"]', '45')

    // VISIBLE: User clicks button
    await page.click('[data-testid="recommend-btn"]')

    // Wait for response and UI update
    await page.waitForLoadState('networkidle')

    // INVISIBLE: Verify API was called with correct payload
    await mockWorkspaceApi.expectRecommendCalled({ availableTime: 45 })

    // VISIBLE: Verify recommendation is displayed
    await expect(page.locator('[data-testid="recommendation-task-title"]')).toContainText(
      'Review API changes',
    )

    await expect(page.locator('[data-testid="recommendation-explanation"]')).toContainText(
      'fits in your available time',
    )

    await expect(page.locator('[data-testid="confidence-score"]')).toContainText('85')

    // VISIBLE: Verify additional task details
    await expect(page.locator('[data-testid="task-priority"]')).toContainText('HIGH')
    await expect(page.locator('[data-testid="task-owner"]')).toContainText('Alex Morgan')
  })

  // ═══════════════════════════════════════════════════════════════════
  // SCENARIO 2: Error Handling - Service Unavailable (503)
  // ═══════════════════════════════════════════════════════════════════

  test('Error: Shows error message when backend returns 503', async ({
    page,
    mockWorkspaceApi,
  }) => {
    // Setup: Mock API to return 503
    await mockWorkspaceApi.recommendError('SERVICE_UNAVAILABLE', 503)

    // VISIBLE: User fills form
    await page.fill('[data-testid="available-time"]', '30')

    // VISIBLE: User clicks button
    await page.click('[data-testid="recommend-btn"]')

    // Wait for error response
    await page.waitForLoadState('networkidle')

    // INVISIBLE: Verify API was called
    await mockWorkspaceApi.expectRecommendCalled({ availableTime: 30 })

    // VISIBLE: Verify error message is displayed
    await expect(page.locator('[data-testid="error-message"]')).toContainText(
      'Backend service is currently unavailable',
    )

    // VISIBLE: Verify error state UI (no recommendation shown)
    await expect(page.locator('[data-testid="recommendation-task-title"]')).not.toBeVisible()
  })

  // ═══════════════════════════════════════════════════════════════════
  // SCENARIO 3: Timeout - Request takes too long (504)
  // ═══════════════════════════════════════════════════════════════════

  test('Timeout: Shows timeout message when request exceeds time limit', async ({
    page,
    mockWorkspaceApi,
  }) => {
    // Setup: Mock API to return 504 (Gateway Timeout)
    await mockWorkspaceApi.recommendError('GATEWAY_TIMEOUT', 504)

    // VISIBLE: User fills form
    await page.fill('[data-testid="available-time"]', '60')

    // VISIBLE: User clicks button
    await page.click('[data-testid="recommend-btn"]')

    // Wait for timeout response
    await page.waitForLoadState('networkidle')

    // INVISIBLE: Verify API was called
    await mockWorkspaceApi.expectRecommendCalled({ availableTime: 60 })

    // VISIBLE: Verify timeout message is displayed
    await expect(page.locator('[data-testid="error-message"]')).toContainText(
      'Request timeout',
    )
  })

  // ═══════════════════════════════════════════════════════════════════
  // SCENARIO 4: Validation - Invalid input
  // ═══════════════════════════════════════════════════════════════════

  test('Validation: Shows error when availableTime is invalid', async ({ page }) => {
    // VISIBLE: User tries to submit without filling availableTime
    await page.click('[data-testid="recommend-btn"]')

    // VISIBLE: Verify validation error is shown
    await expect(page.locator('[data-testid="validation-error"]')).toContainText(
      'Available time is required',
    )

    // INVISIBLE: No API call should be made
    // (Form validation happens client-side before API call)
  })

  test('Validation: Shows error when availableTime is zero', async ({ page }) => {
    // VISIBLE: User fills form with invalid value
    await page.fill('[data-testid="available-time"]', '0')

    // VISIBLE: User clicks button
    await page.click('[data-testid="recommend-btn"]')

    // VISIBLE: Verify validation error is shown
    await expect(page.locator('[data-testid="validation-error"]')).toContainText(
      'Available time must be at least 1 minute',
    )
  })

  // ═══════════════════════════════════════════════════════════════════
  // SCENARIO 5: Optional Filters
  // ═══════════════════════════════════════════════════════════════════

  test('Optional Filters: Can filter by project', async ({ page, mockWorkspaceApi }) => {
    const mockTask = {
      id: 'task_002',
      title: 'Design new dashboard',
      description: 'Create wireframes for the new dashboard',
      status: 'READY',
      priority: 'MEDIUM',
      effortEstimate: 120,
      dueDate: '2026-09-10T17:00:00Z',
      owner: 'Jordan Lee',
      project: 'Design System',
      dependencies: [],
      constraints: [],
    }

    // Setup: Mock API
    await mockWorkspaceApi.recommendSuccess(mockTask)

    // VISIBLE: User fills form with filter
    await page.fill('[data-testid="available-time"]', '120')
    await page.selectOption('[data-testid="project-filter"]', 'Design System')

    // VISIBLE: User clicks button
    await page.click('[data-testid="recommend-btn"]')

    // Wait for response
    await page.waitForLoadState('networkidle')

    // INVISIBLE: Verify API was called with filter
    await mockWorkspaceApi.expectRecommendCalled({
      availableTime: 120,
      projectFilter: 'Design System',
    })

    // VISIBLE: Verify result
    await expect(page.locator('[data-testid="recommendation-task-title"]')).toContainText(
      'Design new dashboard',
    )
  })
})
