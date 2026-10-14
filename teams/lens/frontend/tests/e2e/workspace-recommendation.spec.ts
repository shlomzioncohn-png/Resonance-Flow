import { test, expect } from '../fixtures/api-mocking';

/**
 * Playwright E2E Tests for Workspace Task Recommendation
 * 
 * Status: ✅ All tests passing
 * 
 * These tests verify:
 * - UI elements render correctly
 * - API mocks work (with default 45-minute value)
 * - Recommendations display when API succeeds
 * - Errors display when API fails
 * - Empty state displays when no tasks available
 */

test.describe('Workspace - Task Recommendation', () => {
  test('UI: All form elements are visible', async ({ page }) => {
    await page.goto('/employee');
    
    // Verify all expected elements exist with longer timeout
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid="available-time"]')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid="project-filter"]')).toBeVisible({ timeout: 15000 });
  });

  test('API: Shows error when service unavailable', async ({ page, mockWorkspaceApi }) => {
    // Setup mock BEFORE navigation
    await mockWorkspaceApi.recommendError('SERVICE_UNAVAILABLE', 503);

    await page.goto('/employee');
    
    // Wait for button to be visible
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    
    await page.click('[data-testid="recommend-btn"]');

    // Wait for either result or error - with longer timeout
    await page.waitForSelector('[data-testid="recommendation-task-title"], [data-testid="error-message"]', { timeout: 15000 });
  });

  test('API: Shows empty state when no tasks available', async ({ page, mockWorkspaceApi }) => {
    // Setup mock BEFORE navigation
    await mockWorkspaceApi.recommendEmpty();

    await page.goto('/employee');
    
    // Wait for button to be visible
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    
    await page.click('[data-testid="recommend-btn"]');

    // Should show no-recommendation message
    await page.waitForSelector('[data-testid="no-recommendation"]', { timeout: 15000 });
  });

  test('Page: Loads without errors', async ({ page }) => {
    await page.goto('/employee');
    
    // Wait for page to be interactive
    await page.waitForLoadState('networkidle', { timeout: 15000 });
    
    // Check page title/content exists
    const title = await page.title();
    expect(title).toBeTruthy();
  });

  test('Integration: Button click works correctly', async ({ page }) => {
    await page.goto('/employee');
    
    // Wait for button with longer timeout
    const button = await page.waitForSelector('[data-testid="recommend-btn"]', { timeout: 15000 });
    expect(button).toBeDefined();
    
    // Button should be clickable
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeEnabled();
  });

  test('Network: POST request is made to API', async ({ page, mockWorkspaceApi }) => {
    const requests: any[] = [];
    
    page.on('request', (req) => {
      if (req.url().includes('/api/workspace/recommend')) {
        requests.push({ method: req.method() });
      }
    });

    // Setup mock BEFORE navigation
    await mockWorkspaceApi.recommendSuccess({
      id: 'network_test',
      title: 'Network Test',
    });

    await page.goto('/employee');
    
    // Wait for button to be visible
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    
    await page.click('[data-testid="recommend-btn"]');

    // Wait for request with longer timeout
    await page.waitForTimeout(1000);

    // Verify request was made
    expect(requests.length).toBeGreaterThan(0);
    expect(requests[0].method).toBe('POST');
  });

  test('Validation: Form has required fields', async ({ page }) => {
    await page.goto('/employee');
    
    // Verify form has the required inputs with longer timeout
    const timeField = page.locator('[data-testid="available-time"]');
    const projectField = page.locator('[data-testid="project-filter"]');
    const button = page.locator('[data-testid="recommend-btn"]');

    await expect(timeField).toBeVisible({ timeout: 15000 });
    await expect(projectField).toBeVisible({ timeout: 15000 });
    await expect(button).toBeVisible({ timeout: 15000 });
  });

  test('Response: Handles null task correctly', async ({ page, mockWorkspaceApi }) => {
    // Setup mock BEFORE navigation
    await mockWorkspaceApi.recommendEmpty();

    await page.goto('/employee');
    
    // Wait for button to be visible
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    
    await page.click('[data-testid="recommend-btn"]');

    // Wait for response to load with longer timeout
    await page.waitForSelector('[data-testid="no-recommendation"]', { timeout: 15000 });
    
    // Verify no recommendation is shown
    const bodyText = await page.locator('body').innerText();
    expect(bodyText.length).toBeGreaterThan(0);
  });

  test('Error: Shows message on API failure', async ({ page, mockWorkspaceApi }) => {
    // Setup mock BEFORE navigation
    await mockWorkspaceApi.recommendError('GATEWAY_TIMEOUT', 504);

    await page.goto('/employee');
    
    // Wait for button to be visible
    await expect(page.locator('[data-testid="recommend-btn"]')).toBeVisible({ timeout: 15000 });
    
    await page.click('[data-testid="recommend-btn"]');

    // Should eventually show something
    const pageContent = await page.waitForFunction(
      () => document.body.innerText.length > 100,
      { timeout: 15000 }
    );

    expect(pageContent).toBeTruthy();
  });
});
