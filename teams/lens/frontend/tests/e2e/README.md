# LENS Frontend E2E Tests with Playwright

## Overview

E2E tests verify both **VISIBLE** (UI) and **INVISIBLE** (API) behavior using Playwright.

### VISIBLE Testing
- User can see the form
- User can interact with buttons/inputs
- UI updates after user action
- Error messages display correctly

### INVISIBLE Testing
- Frontend sends correct API payload
- API responses are handled properly
- Network errors are caught and displayed

## Running Tests

```bash
# Run all E2E tests
npm run test:e2e

# Run with UI mode (visual debugging)
npm run test:e2e:ui

# Run with debug mode (step through)
npm run test:e2e:debug

# Run tests for specific browser
npm run test:e2e:chromium
```

## Test Structure

### Fixtures (`../fixtures/api-mocking.ts`)

Provides `mockWorkspaceApi` fixture with:
- `recommendSuccess(task)` - Mock successful API response
- `recommendError(errorCode, statusCode)` - Mock error response
- `expectRecommendCalled(payload)` - Verify API was called with correct payload

**Example:**
```typescript
test('shows recommendation', async ({ page, mockWorkspaceApi }) => {
  // Setup mock
  await mockWorkspaceApi.recommendSuccess({ id: 'task_001', title: 'Task' })
  
  // User interaction
  await page.fill('[data-testid="available-time"]', '45')
  await page.click('[data-testid="recommend-btn"]')
  
  // Verify API (INVISIBLE)
  await mockWorkspaceApi.expectRecommendCalled({ availableTime: 45 })
  
  // Verify UI (VISIBLE)
  await expect(page.locator('[data-testid="task-title"]')).toContainText('Task')
})
```

## Test Scenarios

### 1. Happy Path ✅
- User submits available time
- API returns task recommendation
- UI displays task details

**File:** `workspace-recommendation.spec.ts::Happy Path`

### 2. Error Handling ❌
- User submits form
- API returns 503 SERVICE_UNAVAILABLE
- UI displays error message

**File:** `workspace-recommendation.spec.ts::Error`

### 3. Timeout ⏱️
- User submits form
- API takes too long (504 GATEWAY_TIMEOUT)
- UI displays timeout message

**File:** `workspace-recommendation.spec.ts::Timeout`

### 4. Validation 🚫
- User submits invalid form
- Client-side validation prevents API call
- UI displays validation error

**File:** `workspace-recommendation.spec.ts::Validation`

## Data Attributes Required in UI

For tests to work, the Frontend components must include these `data-testid` attributes:

```typescript
// Recommendation form
<input data-testid="available-time" />
<select data-testid="project-filter" />
<button data-testid="recommend-btn" />

// Recommendation result
<div data-testid="recommendation-task-title" />
<div data-testid="recommendation-explanation" />
<div data-testid="confidence-score" />
<div data-testid="task-priority" />
<div data-testid="task-owner" />

// Error messages
<div data-testid="error-message" />
<div data-testid="validation-error" />
```

## How API Mocking Works

1. **Intercept Request:**
   ```typescript
   await page.route('**/api/workspace/recommend', async (route) => {
     // route.request() = incoming request
   })
   ```

2. **Verify Payload (INVISIBLE):**
   ```typescript
   const request = route.request()
   const body = request.postDataJSON()
   expect(body.availableTime).toBe(45)
   ```

3. **Mock Response:**
   ```typescript
   await route.fulfill({
     status: 200,
     body: JSON.stringify({ task: {...}, confidenceScore: 85 })
   })
   ```

4. **Assert UI (VISIBLE):**
   ```typescript
   await expect(page.locator('[data-testid="task-title"]')).toContainText('...')
   ```

## Common Patterns

### Verify API was called with specific payload
```typescript
await mockWorkspaceApi.expectRecommendCalled({
  availableTime: 45,
  projectFilter: 'Platform Service'
})
```

### Mock successful response
```typescript
await mockWorkspaceApi.recommendSuccess({
  id: 'task_001',
  title: 'Task Title',
  // ...
})
```

### Mock error response
```typescript
await mockWorkspaceApi.recommendError('SERVICE_UNAVAILABLE', 503)
```

## Debugging Tips

### 1. Use UI Mode
```bash
npm run test:e2e:ui
```
Visually debug tests in browser.

### 2. Use Debug Mode
```bash
npm run test:e2e:debug
```
Step through tests in Playwright Inspector.

### 3. View Screenshots/Videos
Failures generate screenshots and videos in `test-results/`.

### 4. Add Debug Logs
```typescript
await page.evaluate(() => console.log('Debug info'))
```

### 5. Inspect Network Requests
```typescript
page.on('request', req => console.log('>>>', req.url()))
page.on('response', res => console.log('<<<', res.url(), res.status()))
```

## Next Steps

1. ✅ Playwright configured
2. ✅ E2E tests structure created
3. ✅ API mocking fixture implemented
4. ⏳ **Create Frontend UI components** (add `data-testid` attributes)
5. ⏳ **Run tests** - `npm run test:e2e`

## Questions?

See [Playwright Docs](https://playwright.dev/docs/intro) for more.
