# Postman Auth Integration Testing Checklist

## Test Credentials
- **Email:** testuser123@example.com (or any Inbox user)
- **Password:** Password123!
- **User ID:** 2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a
- **Tenant ID:** 00000000-0000-0000-0000-000000000001
- **System Admin ID:** 00000000-0000-0000-0000-000000000002

## Service Ports
- **Inbox:** http://localhost:8082
- **Gateway:** http://localhost:8080
- **BFF:** http://localhost:8085
- **Frontend:** http://localhost:3000
- **Chain:** http://localhost:8081 (Docker only)
- **Kafka:** http://localhost:9092 (Docker only)

---

## Phase 0: Setup & Prerequisites

### Test 0.1: Check Frontend is Connected to Gateway
- **Frontend:** Start dev server at http://localhost:3000
- **Gateway:** Must be running on http://localhost:8080
- **Environment:** Frontend .env has `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`
- **MSW Status:** MSW should NOT mock `/auth/login` (handler removed), will bypass to Gateway
- **Status:** ⬜ Not Tested

---

## Phase 1: Inbox Authentication Service

### Test 1.1: Verify Auth Endpoint (Inbox)
- **Endpoint:** `POST http://localhost:8082/api/v1/auth/verify`
- **Headers:**
  ```
  Content-Type: application/json
  ```
- **Body:**
  ```json
  {
    "login": "testuser123",
    "password": "Password123!"
  }
  ```
- **Expected Response:** `200 OK`
- **Response should contain:**
  - `userId`: 2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a
  - `displayName`: Test User
  - `role`: EMPLOYEE
  - `tenantId`: 00000000-0000-0000-0000-000000000001
- **Status:** ⬜ Not Tested

### Test 1.2: Auth Failure with Wrong Password
- **Endpoint:** `POST http://localhost:8082/api/v1/auth/verify`
- **Body:**
  ```json
  {
    "login": "testuser123",
    "password": "WrongPassword123!"
  }
  ```
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

### Test 1.3: Auth Failure with Non-existent User
- **Endpoint:** `POST http://localhost:8082/api/v1/auth/verify`
- **Body:**
  ```json
  {
    "login": "nonexistent",
    "password": "Password123!"
  }
  ```
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

---

## Phase 2: Gateway JWT Token Generation

### Test 2.1: Generate JWT Token from Auth Response
- **Endpoint:** `POST http://localhost:8080/auth/login`
- **Headers:**
  ```
  Content-Type: application/json
  ```
- **Body:**
  ```json
  {
    "login": "testuser123",
    "password": "Password123!"
  }
  ```
- **Expected Response:** `200 OK`
- **Response JSON should contain:**
  - `expiresIn`: Token expiry in seconds (e.g., 3600)
  - `user.id`: 2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a
  - `user.displayName`: Test User
  - `user.role`: EMPLOYEE
- **Response headers should contain:** `Set-Cookie: accessToken=<JWT>; HttpOnly; Secure; SameSite=Strict`
- **Token NOT in response body** (only in Set-Cookie header)
- **Extract JWT from Set-Cookie header for next tests**
- **Status:** ⬜ Not Tested

### Test 2.2: Validate JWT Token Format
- **Take token from Test 2.1**
- **Decode at:** https://jwt.io
- **Verify:**
  - Header: `{"alg":"RS256","typ":"JWT"}`
  - Payload contains: `sub: 2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a`
  - Payload contains: `role: EMPLOYEE`
  - Payload contains: `name: Test User`
  - Signature verifies (RS256)
- **Status:** ⬜ Not Tested

---

## Phase 3: Frontend Login Integration

### Test 3.1: Frontend Login Page Loads
- **URL:** http://localhost:3000/login
- **Expected:** Login form with Email and Password fields
- **Verify:**
  - Label says "Email" (not "Username")
  - "Sign in" button is present
  - No console errors
- **Status:** ⬜ Not Tested

### Test 3.2: Frontend Login with Valid Credentials
- **URL:** http://localhost:3000/login
- **Fill:** 
  - Email: `testuser123@example.com`
  - Password: `Password123!`
- **Click:** "Sign in"
- **Expected Behavior:**
  1. Request goes to `POST /auth/login` on Gateway (port 8080)
  2. Gateway validates against Inbox
  3. Gateway sets HttpOnly cookie with JWT token
  4. Frontend receives user info (no token in response body)
  5. User redirected to `/employee` (for EMPLOYEE role) or `/manager` (for TENANT_ADMIN/TEAM_LEAD)
- **Verify in DevTools:**
  - Check Application → Cookies: `accessToken` HttpOnly cookie should be present
  - localStorage should be **EMPTY** (no token stored there)
  - Check Network tab: POST request to `http://localhost:8080/auth/login` should succeed with `Set-Cookie` header
  - Response body should contain `expiresIn` and `user` object (but NO `accessToken`)
  - URL should change to `/employee` or `/manager`
- **Status:** ⬜ Not Tested

### Test 3.3: Frontend Login with Wrong Password
- **URL:** http://localhost:3000/login
- **Fill:**
  - Email: `testuser123@example.com`
  - Password: `WrongPassword123!`
- **Click:** "Sign in"
- **Expected:**
  - Error alert appears: "We couldn't verify who you are. Please sign in again."
  - User stays on login page
  - localStorage remains empty
  - Verify Network tab: POST request returns `401 Unauthorized`
- **Status:** ⬜ Not Tested

### Test 3.4: Frontend Login with Non-existent User
- **URL:** http://localhost:3000/login
- **Fill:**
  - Email: `nonexistent@example.com`
  - Password: `Password123!`
- **Click:** "Sign in"
- **Expected:**
  - Error alert appears
  - User stays on login page
  - Verify Network tab: POST request returns `401 Unauthorized`
- **Status:** ⬜ Not Tested

### Test 3.5: Token Sent in Cookies (Not Authorization Header)
- **After successful login (Test 3.2)**
- **URL:** http://localhost:3000/employee (or /manager)
- **Open DevTools → Network tab**
- **Make any request (e.g., load a page with API calls)**
- **Verify in Network tab:**
  - API requests have `Cookie: accessToken=<JWT_TOKEN>` header
  - NO `Authorization: Bearer` header
  - Token in cookie matches the one from Test 2.1 (Set-Cookie header)
- **Status:** ⬜ Not Tested

### Test 3.6: Role Mapping - EMPLOYEE Routes to /employee
- **Credentials:** testuser123@example.com (role: EMPLOYEE)
- **After login:** Should redirect to `http://localhost:3000/employee`
- **Verify:** URL shows `/employee`
- **Status:** ⬜ Not Tested

### Test 3.7: Role Mapping - TENANT_ADMIN Routes to /manager
- **Create TENANT_ADMIN user in Inbox** (if not already available)
- **Credentials:** Use TENANT_ADMIN user
- **After login:** Should redirect to `http://localhost:3000/manager`
- **Verify:** URL shows `/manager`
- **Status:** ⬜ Not Tested

---

## Phase 4: Gateway Authorization with JWT

### Test 4.1: Access Protected Endpoint with Valid JWT
- **Endpoint:** `GET http://localhost:8080/api/workspace/tasks`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  ```
- **Expected Response:** `200 OK`
- **Status:** ⬜ Not Tested

### Test 4.2: Access Protected Endpoint with Invalid JWT
- **Endpoint:** `GET http://localhost:8080/api/workspace/tasks`
- **Headers:**
  ```
  Authorization: Bearer invalid.token.here
  ```
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

### Test 4.3: Access Protected Endpoint without Authorization Header
- **Endpoint:** `GET http://localhost:8080/api/workspace/tasks`
- **Headers:** (none)
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

---

## Phase 5: BFF Integration & Token Forwarding

### Test 5.1: BFF Endpoint with JWT Token
- **Endpoint:** `GET http://localhost:8082/api/v1/users`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  ```
- **Expected Response:** `200 OK`
- **Response should be forwarded from Inbox service**
- **Status:** ⬜ Not Tested

### Test 5.2: BFF Endpoint with X-User-Id Header
- **Endpoint:** `GET http://localhost:8082/api/v1/users/{USER_ID}`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  X-User-Id: 2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a
  ```
- **Expected Response:** `200 OK`
- **BFF should extract userId from JWT and add X-Actor-Id header when forwarding to Inbox**
- **Status:** ⬜ Not Tested

### Test 5.3: BFF Without Authorization Header
- **Endpoint:** `GET http://localhost:8082/api/v1/users`
- **Headers:** (none)
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

---

## Phase 6: Gateway → BFF → Inbox End-to-End Flow

### Test 6.1: Create User via BFF (with JWT)
- **Endpoint:** `POST http://localhost:8082/api/v1/users`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  Content-Type: application/json
  ```
- **Body:**
  ```json
  {
    "tenant_id": "00000000-0000-0000-0000-000000000001",
    "display_name": "Another Test User",
    "username": "testuser456",
    "email": "testuser456@example.com",
    "role": "EMPLOYEE",
    "password": "Password123!"
  }
  ```
- **Expected Response:** `201 Created`
- **BFF should extract userId from JWT and pass X-Actor-Id to Inbox**
- **Status:** ⬜ Not Tested

### Test 6.2: Get User Details via BFF (with JWT)
- **Endpoint:** `GET http://localhost:8082/api/v1/users/2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  ```
- **Expected Response:** `200 OK`
- **Response contains user created in Phase 1**
- **Status:** ⬜ Not Tested

---

## Phase 7: Permissions & Role-Based Access Control

### Test 7.1: EMPLOYEE Accessing Admin Endpoint
- **Endpoint:** `POST http://localhost:8082/api/v1/admin/users`
- **Headers:**
  ```
  Authorization: Bearer {JWT_TOKEN_FROM_TEST_3.2}
  Content-Type: application/json
  ```
- **Body:** (any user creation request)
- **Expected Response:** `403 Forbidden`
- **User role: EMPLOYEE should not be able to create users**
- **Status:** ⬜ Not Tested

### Test 7.2: Create Admin User for Testing
- **Endpoint:** `POST http://localhost:8082/api/v1/users`
- **Headers:**
  ```
  Content-Type: application/json
  X-Actor-Id: 00000000-0000-0000-0000-000000000002
  ```
- **Body:**
  ```json
  {
    "tenant_id": "00000000-0000-0000-0000-000000000001",
    "display_name": "Admin User",
    "username": "adminuser",
    "email": "admin@example.com",
    "role": "TENANT_ADMIN",
    "password": "AdminPass123!"
  }
  ```
- **Expected Response:** `201 Created`
- **Status:** ⬜ Not Tested

### Test 7.3: Admin User Can Create Other Users
- **Endpoint:** `POST http://localhost:8080/auth/login`
- **Body:**
  ```json
  {
    "login": "adminuser",
    "password": "AdminPass123!"
  }
  ```
- **Expected Response:** `200 OK` with JWT token
- **Save this token for Test 7.4**
- **Status:** ⬜ Not Tested

### Test 7.4: Use Admin Token to Create User
- **Endpoint:** `POST http://localhost:8082/api/v1/users`
- **Headers:**
  ```
  Authorization: Bearer {ADMIN_JWT_TOKEN_FROM_TEST_7.3}
  Content-Type: application/json
  ```
- **Body:**
  ```json
  {
    "tenant_id": "00000000-0000-0000-0000-000000000001",
    "display_name": "New User by Admin",
    "username": "newuser",
    "email": "newuser@example.com",
    "role": "EMPLOYEE",
    "password": "NewPass123!"
  }
  ```
- **Expected Response:** `201 Created`
- **Admin should be able to create users**
- **Status:** ⬜ Not Tested

---

## Phase 9: HttpOnly Cookie Security (NEW)

### Test 9.1: Verify Token is NOT in localStorage
- **After successful login (Test 3.2)**
- **Open DevTools → Console**
- **Run:** `window.localStorage.getItem('lens.auth.token')`
- **Expected:** `null` (localStorage should be empty)
- **Explanation:** Token is only in HttpOnly cookie, not accessible from JavaScript
- **Status:** ⬜ Not Tested

### Test 9.2: Verify Token is in HttpOnly Cookie
- **After successful login (Test 3.2)**
- **Open DevTools → Application → Cookies**
- **Look for:** `accessToken` cookie
- **Verify:**
  - Domain: localhost (or your domain)
  - Path: /
  - HttpOnly: ✓ (checked)
  - Secure: ✓ (checked) in HTTPS
  - SameSite: Strict
  - Value: JWT token (long string)
- **Status:** ⬜ Not Tested

### Test 9.3: XSS Script Cannot Access Token
- **After successful login (Test 3.2)**
- **Open DevTools → Console**
- **Run:** `document.cookie`
- **Expected:** No `accessToken` visible (because it's HttpOnly)
- **Explanation:** JavaScript cannot read HttpOnly cookies, preventing XSS token theft
- **Status:** ⬜ Not Tested

### Test 9.4: Response Body Does NOT Contain Token
- **After login (Test 2.1 or 3.2)**
- **Check Response body in Network tab**
- **Verify:**
  - `accessToken` field is NOT present
  - Only `expiresIn` and `user` object present
  - Response should look like:
    ```json
    {
      "expiresIn": 3600,
      "user": {
        "id": "2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a",
        "displayName": "Test User",
        "role": "EMPLOYEE"
      }
    }
    ```
- **Status:** ⬜ Not Tested

---

## Phase 8 (old Phase 9): Token Expiration & Refresh (if applicable)

## Phase 8 (old Phase 9): Token Expiration & Refresh (if applicable)

### Test 8.1: Check JWT Expiration Time
- **Take token from Test 2.1 (from Set-Cookie header)**
- **Decode at:** https://jwt.io
- **Verify:**
  - `exp` claim exists
  - `exp` time is in the future (not expired)
  - Calculated expiration: Unix timestamp conversion
- **Status:** ⬜ Not Tested

### Test 8.2: Use Expired Token
- **Wait for token to expire (or create one manually with past exp)**
- **Endpoint:** `GET http://localhost:8080/api/workspace/tasks`
- **Headers:**
  ```
  Authorization: Bearer {EXPIRED_TOKEN}
  ```
- **Expected Response:** `401 Unauthorized`
- **Status:** ⬜ Not Tested

---

## Phase 10 (old Phase 8): Error Handling & Security

## Phase 10 (old Phase 8): Error Handling & Security

### Test 10.1: SQL Injection in Login Field
- **Endpoint:** `POST http://localhost:8082/api/v1/auth/verify`
- **Body:**
  ```json
  {
    "login": "admin' OR '1'='1",
    "password": "anything"
  }
  ```
- **Expected Response:** `401 Unauthorized` (not SQL injection)
- **Status:** ⬜ Not Tested

### Test 10.2: Account Lockout After Failed Attempts
- **Run Test 1.2 multiple times (e.g., 5 times)**
- **After X failed attempts, account should lock**
- **Expected Response:** `429 Too Many Requests` or `403 Forbidden`
- **Status:** ⬜ Not Tested

### Test 10.3: CORS Headers Check
- **Endpoint:** `OPTIONS http://localhost:8080/api/workspace/tasks`
- **Headers:**
  ```
  Origin: http://localhost:3000
  ```
- **Expected Response:** `200 OK`
- **Response should have Access-Control-Allow-* headers**
- **Status:** ⬜ Not Tested

---

## Summary

- **Total Tests:** 31
- **Completed:** 0
- **Passed:** 0
- **Failed:** 0

### Test Results
| Phase | Tests | Status |
|-------|-------|--------|
| Phase 1: Inbox Auth | 3 | ⬜ |
| Phase 2: Gateway JWT | 2 | ⬜ |
| Phase 3: Gateway Auth | 3 | ⬜ |
| Phase 4: BFF Integration | 3 | ⬜ |
| Phase 5: End-to-End | 2 | ⬜ |
| Phase 6: RBAC | 4 | ⬜ |
| Phase 7: Token Expiration | 2 | ⬜ |
| Phase 8: Security | 3 | ⬜ |
| Phase 9: HttpOnly Cookies | 4 | ⬜ |

---

## Notes for Postman Setup

### Environment Variables
Create a Postman environment with:
```json
{
  "inbox_url": "http://localhost:8082",
  "gateway_url": "http://localhost:8080",
  "bff_url": "http://localhost:8081",
  "username": "testuser123",
  "password": "Password123!",
  "jwt_token": "",
  "admin_jwt_token": "",
  "user_id": "2fc5f6bb-9e60-4aeb-84ab-abd81bdbf99a"
}
```

### Pre-request Script (for storing JWT)
```javascript
// After login request, store token
if (pm.response.code === 200) {
    const response = pm.response.json();
    pm.environment.set("jwt_token", response.token);
}
```

### Authorization Header Template
```
Authorization: Bearer {{jwt_token}}
```

---

## Running the Tests

1. **Start all services:** `docker-compose up -d`
2. **Create test user** (if not already created)
3. **Import this checklist into Postman**
4. **Run tests in order** (Phase 1 → Phase 8)
5. **Document results** and any failures
6. **Update this file** with actual results

