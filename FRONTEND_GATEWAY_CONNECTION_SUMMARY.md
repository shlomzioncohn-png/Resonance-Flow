# Frontend → Gateway Login Connection - Integration Summary

**Date:** October 20, 2026  
**Status:** ✅ COMPLETE - Ready for Testing  
**Team:** LENS (Auth Integration Track)

---

## Overview

Completed the connection between **Login Frontend** and **LENS Gateway**. The Frontend now communicates directly with the Gateway to authenticate users, replacing previous MSW (Mock Service Worker) mocks.

### Architecture
```
Frontend Login Form
    ↓ POST /auth/login (email + password)
LENS Gateway (port 8080)
    ↓ calls BFF /internal/auth/verify
BFF (port 8085)
    ↓ calls Inbox /api/v1/auth/verify
Inbox (port 8082, Real Auth, No Mock)
    ↓ returns user info
BFF → maps role → AuthUserResponseDto
    ↓
Gateway → creates JWT → AuthLoginResponseDto
    ↓
Frontend ← receives token + user info
```

---

## Changes Made

### 1. MSW Handlers (`teams/lens/frontend/mocks/handlers.ts`)

**What Changed:**
- ❌ Removed: MSW mock handler for `POST /api/auth/login`
- ✅ Added: Comment explaining handler is removed (Gateway now handles it)
- ✅ Kept: MSW bypass rule (`onUnhandledRequest: 'bypass'`) so unhandled requests go to Gateway

**Impact:**
- Frontend login requests now bypass MSW and hit the real Gateway
- No more mock responses for auth

**Code:**
```typescript
// Old: http.post<never, LoginRequest, ...>(...) // MSW mocked this
// New: (handler removed - MSW will bypass to Gateway)
```

---

### 2. Auth API Types (`teams/lens/frontend/services/authApi.ts`)

**What Changed:**
- ✅ Updated `LoginRequest` parameter: `username` → `login` (email address)
- ✅ Updated `LoginResponse` to include: `accessToken` and `expiresIn`
- ✅ Updated `AuthRole` type:
  - Old: `'manager' | 'employee'`
  - New: `'EMPLOYEE' | 'TENANT_ADMIN' | 'TEAM_LEAD'`
- ✅ Changed endpoint from `/api/auth/login` to `/auth/login`

**Code:**
```typescript
export type AuthRole = 'EMPLOYEE' | 'TENANT_ADMIN' | 'TEAM_LEAD';

export interface LoginRequest {
  login: string;    // email address (was: username)
  password: string;
}

export interface LoginResponse {
  accessToken: string;  // JWT token (NEW)
  expiresIn: number;    // Token expiry in seconds (NEW)
  id: string;
  displayName: string;
  role: AuthRole;
}

function login(credentials: LoginRequest): Promise<LoginResponse> {
  return api.post<LoginResponse>('/auth/login', credentials);  // was: /api/auth/login
}
```

---

### 3. API Client (`teams/lens/frontend/services/api.ts`)

**What Changed:**
- ✅ Added token handling: JWT token from localStorage sent as `Authorization: Bearer <token>`
- ✅ Token automatically included in all requests when available

**Impact:**
- All subsequent API calls (after login) include the JWT token
- Token lifecycle managed via localStorage

**Code:**
```typescript
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = { 'Content-Type': 'application/json', ...init?.headers };
  
  // Add JWT from localStorage if available
  const token = typeof window !== 'undefined' ? window.localStorage.getItem('lens.auth.token') : null;
  if (token) {
    (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
  }
  
  // ... rest of request logic
}
```

---

### 4. Login Page (`teams/lens/frontend/app/(public)/login/page.tsx`)

**What Changed:**
- ✅ Changed input label from "Username" to "Email"
- ✅ Changed state variable from `username` to `email`
- ✅ Pass email as `login` field to API
- ✅ Save JWT token to localStorage after successful login
- ✅ Updated role-based routing:
  - Old: `role === 'manager'` → `/manager`, else → `/employee`
  - New: `['TENANT_ADMIN', 'TEAM_LEAD']` → `/manager`, else → `/employee`

**Code:**
```typescript
const [email, setEmail] = useState('');  // was: username

const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
  // ...
  try {
    const response = await login({ login: email, password });  // was: username
    window.localStorage.setItem('lens.auth.token', response.accessToken);  // NEW
    setRole(response.role);
    router.push(['TENANT_ADMIN', 'TEAM_LEAD'].includes(response.role) ? '/manager' : '/employee');
  } catch (err) {
    setError(getErrorMessage(err));
  }
};
```

---

### 5. Role Provider (`teams/lens/frontend/components/layout/RoleProvider/RoleProvider.tsx`)

**What Changed:**
- ✅ Updated `Role` type to use `AuthRole` from authApi (supports new role values)
- ✅ Updated localStorage validation to accept new role values
- ✅ Old validation: `stored === 'manager' || stored === 'employee'`
- ✅ New validation: `stored === 'EMPLOYEE' || stored === 'TENANT_ADMIN' || stored === 'TEAM_LEAD'`

**Code:**
```typescript
export type Role = AuthRole | null;  // AuthRole = 'EMPLOYEE' | 'TENANT_ADMIN' | 'TEAM_LEAD'

// Old: if (stored === 'manager' || stored === 'employee')
// New:
if (stored === 'EMPLOYEE' || stored === 'TENANT_ADMIN' || stored === 'TEAM_LEAD') {
  setRoleState(stored as AuthRole);
}
```

---

## Data Flow

### 1. User Submits Login Form
```
Email: testuser123@example.com
Password: Password123!
         ↓
POST /auth/login {login: "testuser123@example.com", password: "Password123!"}
```

### 2. Gateway Processes Request
```
Gateway receives POST /auth/login
         ↓
Calls BFF /internal/auth/verify
         ↓
Returns JWT token + user info {
  accessToken: "eyJhbGci...",
  expiresIn: 3600,
  user: { id: "...", displayName: "Test User", role: "EMPLOYEE" }
}
```

### 3. Frontend Receives Response
```
Frontend stores:
  - localStorage["lens.auth.token"] = JWT
  - setRole("EMPLOYEE")
  - Redirect to /employee
  
Subsequent requests include:
  - Header: Authorization: Bearer eyJhbGci...
```

---

## Testing

### Prerequisites
- **Inbox running:** http://localhost:8082
- **Gateway running:** http://localhost:8080
- **Frontend running:** http://localhost:3000
- **BFF running:** http://localhost:8085

### Quick Test
1. Navigate to http://localhost:3000/login
2. Enter email: `testuser123@example.com`
3. Enter password: `Password123!`
4. Click "Sign in"
5. Should redirect to http://localhost:3000/employee

### Verification Checklist
- [ ] Login form shows "Email" label (not "Username")
- [ ] Network tab shows POST to `http://localhost:8090/auth/login` (not mocked by MSW)
- [ ] Response contains `accessToken` and `expiresIn`
- [ ] Token stored in localStorage
- [ ] User redirected to correct role-based page (/employee or /manager)
- [ ] Subsequent API calls include `Authorization: Bearer <token>` header

---

## Files Modified

| File | Changes |
|------|---------|
| `teams/lens/frontend/mocks/handlers.ts` | Removed POST /auth/login MSW handler |
| `teams/lens/frontend/services/authApi.ts` | Updated LoginRequest/Response types, endpoint path |
| `teams/lens/frontend/services/api.ts` | Added token header to all requests |
| `teams/lens/frontend/app/(public)/login/page.tsx` | Changed username→email, added token storage, updated role routing |
| `teams/lens/frontend/components/layout/RoleProvider/RoleProvider.tsx` | Updated Role type and localStorage validation |
| `POSTMAN_AUTH_TESTING_CHECKLIST.md` | Added Frontend testing phases |

---

## Environment Configuration

### Required .env
```bash
# teams/lens/frontend/.env
NEXT_PUBLIC_API_BASE_URL=http://localhost:8090  # Gateway URL
```

### Optional (for toggling MSW)
```bash
# Default (empty = MSW OFF, use real Gateway)
NEXT_PUBLIC_API_MOCKING=

# Only set to "enabled" if you want MSW to mock unimplemented endpoints
# But now that login is connected to Gateway, this is less needed
NEXT_PUBLIC_API_MOCKING=enabled  # (not recommended for auth testing)
```

---

## Next Steps

1. ✅ **Frontend connected to Gateway** (THIS TASK)
2. ⏳ **Test end-to-end flow** (manual testing or Playwright E2E)
3. ⏳ **Handle token refresh** (if needed, currently JWT expires after 1 hour)
4. ⏳ **Add logout functionality** (clear token from localStorage + call Gateway logout)
5. ⏳ **Add session persistence** (restore token on page reload if still valid)

---

## Rollback (if needed)

If something breaks, the changes can be reverted by:
1. Restoring MSW handler in `mocks/handlers.ts`
2. Reverting authApi.ts to use `username` instead of `login`
3. Reverting api.ts to not send Authorization header
4. Reverting login page to old role types

All changes are isolated to Frontend and don't affect Gateway/BFF/Inbox.

---

## Notes

- **No Gateway changes needed** - it already has `/auth/login` endpoint
- **No BFF changes needed** - it already handles `/internal/auth/verify`
- **No Inbox changes needed** - it already has `/api/v1/auth/verify`
- **Token is sent as header** (not cookie) for Frontend requests
- **Gateway also sets HttpOnly cookie** for additional security (frontend doesn't rely on it)
- **Roles changed from frontend-specific to backend-authoritative** (INBOX defines roles)

---

## Questions / Troubleshooting

**Q: Frontend still showing username field?**
A: Clear browser cache and restart dev server. Next.js might be caching old build.

**Q: Getting 401 after login?**
A: Check that token is in localStorage (`lens.auth.token`) and is valid JWT. Check Gateway logs for why it might be rejecting it.

**Q: API calls not including Authorization header?**
A: Check that token exists in localStorage. Check Network tab to verify header is present.

**Q: Wrong redirect after login?**
A: Check that user role from Inbox matches expectations. Roles must be exactly: `EMPLOYEE`, `TENANT_ADMIN`, or `TEAM_LEAD`.

---

**End of Summary**
