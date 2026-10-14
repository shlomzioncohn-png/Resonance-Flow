# Playwright Setup for LENS Frontend

**Date:** September 6, 2026  
**Status:** ✅ READY TO RUN

## What Was Created

### 1. Configuration

- ✅ `playwright.config.ts` - Playwright configuration with Chrome/Firefox/Safari
- ✅ Base URL set to http://localhost:3000
- ✅ Screenshots/videos on failure enabled
- ✅ HTML reporter configured

### 2. Test Fixtures

- ✅ `tests/fixtures/api-mocking.ts` - Reusable API mock fixture
  - `mockWorkspaceApi.recommendSuccess(task)` - Mock successful response
  - `mockWorkspaceApi.recommendError(errorCode, statusCode)` - Mock error
  - `mockWorkspaceApi.expectRecommendCalled(payload)` - Verify API call

### 3. E2E Tests

- ✅ `tests/e2e/workspace-recommendation.spec.ts` - 5 test scenarios
  1. Happy Path ✅ - User gets recommendation
  2. Error Handling ❌ - 503 SERVICE_UNAVAILABLE
  3. Timeout ⏱️ - 504 GATEWAY_TIMEOUT
  4. Validation 🚫 - Invalid input
  5. Optional Filters 🔍 - Filter by project

### 4. NPM Scripts

```json
"test:e2e": "playwright test",
"test:e2e:ui": "playwright test --ui",
"test:e2e:debug": "playwright test --debug",
"test:e2e:chromium": "playwright test --project=chromium"
```

### 5. Documentation

- ✅ `tests/e2e/README.md` - How to run tests, patterns, debugging
- ✅ `PLAYWRIGHT_SETUP.md` - This file

## How It Works

### VISIBLE + INVISIBLE Testing Pattern

**Example: Task Recommendation**

```typescript
test('shows recommendation when API returns success', async ({ page, mockWorkspaceApi }) => {
  // 1. INVISIBLE: Mock API response
  await mockWorkspaceApi.recommendSuccess({
    id: 'task_001',
    title: 'Review API changes',
  });

  // 2. VISIBLE: User interaction (fills form)
  await page.fill('[data-testid="available-time"]', '45');

  // 3. VISIBLE: User interaction (clicks button)
  await page.click('[data-testid="recommend-btn"]');

  // 4. INVISIBLE: Verify API was called correctly
  await mockWorkspaceApi.expectRecommendCalled({ availableTime: 45 });

  // 5. VISIBLE: Verify UI updated
  await expect(page.locator('[data-testid="task-title"]')).toContainText('Review API changes');
});
```

### API Interception Flow

```
Frontend calls: POST /api/workspace/recommend { availableTime: 45 }
                          ↓
    page.route() intercepts the request
                          ↓
    Test captures the request payload
                          ↓
    Test verifies: { availableTime: 45 } ✅
                          ↓
    Test returns mock response: { task: {...}, confidenceScore: 85 }
                          ↓
    Frontend receives mocked data
                          ↓
    UI updates with mock data
                          ↓
    Test asserts: UI contains "Review API changes" ✅
```

## Requirements for Frontend Components

Tests expect these `data-testid` attributes in the UI:

### Form Elements

```html
<input data-testid="available-time" />
<select data-testid="project-filter" />
<button data-testid="recommend-btn" />
```

### Result Display

```html
<div data-testid="recommendation-task-title" />
<div data-testid="recommendation-explanation" />
<div data-testid="confidence-score" />
<div data-testid="task-priority" />
<div data-testid="task-owner" />
```

### Error Messages

```html
<div data-testid="error-message" />
<div data-testid="validation-error" />
```

## Running Tests

### 1. Start Frontend

```bash
npm run dev
# Frontend will be available at http://localhost:3000
```

### 2. Run Tests

```bash
# Run all tests (headless)
npm run test:e2e

# Run with UI (visual debugging)
npm run test:e2e:ui

# Run with debug mode (step through)
npm run test:e2e:debug

# Run specific browser only
npm run test:e2e:chromium
```

### 3. View Results

- HTML Report: `playwright-report/index.html`
- Screenshots: `test-results/`
- Videos: `test-results/`

## Test Scenarios Covered

| Scenario        | VISIBLE                | INVISIBLE                   | Status |
| --------------- | ---------------------- | --------------------------- | ------ |
| Happy Path      | UI shows result        | API called correctly        | ✅     |
| Error 503       | Error message shown    | API returned 503            | ✅     |
| Timeout 504     | Timeout message shown  | API returned 504            | ✅     |
| Validation      | Validation error shown | No API call made            | ✅     |
| Optional Filter | Result with filter     | API payload includes filter | ✅     |

## API Endpoints Tested

- `POST /api/workspace/recommend` - Get task recommendation
  - Request: `{ availableTime: number, projectFilter?: string, minPriority?: string }`
  - Success Response: `{ task: {...}, confidenceScore: number, ... }`
  - Error Response: `{ status: "ERROR", errorCode: "...", message: "...", ... }`

## Architecture

```
Frontend (Next.js)
    ↓
Services (api.ts, workspaceApi.ts)
    ↓
[Playwright Intercepts]
    ↓
Gateway (would route to BFF in real scenario)
    ↓
[Test Mocks Response]
    ↓
Frontend receives mock data
    ↓
UI updates
    ↓
Test asserts
```

## Next Steps

1. ✅ Playwright configured
2. ✅ Tests structure created
3. ✅ API mocking implemented
4. ⏳ **Create Frontend UI** (add data-testid attributes to components)
5. ⏳ **Run tests** - `npm run test:e2e`

## Debugging

### View test execution

```bash
npm run test:e2e:ui
```

### Step through test

```bash
npm run test:e2e:debug
```

### Check what API was called

Add this to test:

```typescript
page.on('request', (req) => console.log('Request:', req.url()));
page.on('response', (res) => console.log('Response:', res.status()));
```

### View captured screenshots

```
test-results/
├── workspace-recommendation-happy-path-1-failed.png
├── workspace-recommendation-error-503-1-failed.mp4
└── ...
```

## Key Files

| File                                         | Purpose                   |
| -------------------------------------------- | ------------------------- |
| `playwright.config.ts`                       | Playwright configuration  |
| `tests/fixtures/api-mocking.ts`              | Reusable API mock fixture |
| `tests/e2e/workspace-recommendation.spec.ts` | 5 test scenarios          |
| `tests/e2e/README.md`                        | Test documentation        |
| `package.json`                               | Added test scripts        |

## Resources

- [Playwright Docs](https://playwright.dev)
- [API Mocking](https://playwright.dev/docs/mock)
- [Fixtures](https://playwright.dev/docs/test-fixtures)
- [Best Practices](https://playwright.dev/docs/best-practices)

---

**Status:** Ready to implement UI components with data-testid attributes! 🚀
