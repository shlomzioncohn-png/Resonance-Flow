# 📋 Postman Testing Checklist - Full Auth Integration

## Environment Variables
```
GATEWAY_URL       = http://localhost:8086
BFF_URL           = http://localhost:8087
INBOX_URL         = http://localhost:8083
USER_ID           = ef071a8a-1040-3a27-904a-f6352bad4fea
TOKEN             = (יתמלא בעת התחברות)
ROLE              = manager
```

---

## 🔐 LEVEL 1: Inbox Auth Service (Base Layer)

### ✅ 1. Verify Credentials (Inbox)
```
POST http://localhost:8083/api/v1/auth/verify
Content-Type: application/json

{
  "login": "test_user",
  "password": "password123"
}
```
**Expected:** 200 OK + `{ userId, displayName, role, tenantId }`
**Error cases:**
- 400: login/password missing or too long
- 401: login doesn't exist OR password wrong OR user inactive

---

## 🔐 LEVEL 2: Gateway - Token Flow

### ✅ 2. Generate Token via Gateway (if endpoint exists)
```
POST http://localhost:8086/auth/login
Content-Type: application/json

{
  "userId": "ef071a8a-1040-3a27-904a-f6352bad4fea",
  "displayName": "John Manager",
  "role": "MANAGER"
}
```
**Expected:** 200 OK + JWT token + HttpOnly Cookie: accessToken=...
**Check JWT claims:** sub, role, name, iss, iat, exp (via jwt.io)

---

## 🔐 LEVEL 3: Gateway Authorization

### ✅ 3. No Token → Should Fail (if auth enabled)
```
GET http://localhost:8086/api/workspace/tasks
```
**Expected (auth=true):** 401 Unauthorized
**Expected (auth=false):** 200 OK

### ✅ 4. Bearer Token → Success
```
GET http://localhost:8086/api/workspace/tasks
Authorization: Bearer {{TOKEN}}
```
**Expected:** 200 OK

### ✅ 5. Cookie Token → Success
```
GET http://localhost:8086/api/workspace/tasks
Cookie: accessToken={{TOKEN}}
```
**Expected:** 200 OK

### ✅ 6. Invalid Token → 401
```
GET http://localhost:8086/api/workspace/tasks
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```
**Expected:** 401 Unauthorized

### ✅ 7. Expired Token → 401
```
GET http://localhost:8086/api/workspace/tasks
Authorization: Bearer <expired-token>
```
**Expected:** 401 Unauthorized

---

## 🔐 LEVEL 4: BFF - Token Forwarding

### ✅ 8. X-User-Id Header Forwarding
```
GET http://localhost:8087/workspace/tasks
X-User-Id: {{USER_ID}}
```
**Expected:** 200 OK + tasks list

### ✅ 9. Inbox Integration (Mock OFF)
```
GET http://localhost:8087/workspace/tasks
X-User-Id: {{USER_ID}}
```
**Expected:** Real tasks from Inbox (check INBOX_MOCK_ENABLED=false)

### ✅ 10. Inbox Mock (Mock ON)
```
GET http://localhost:8087/workspace/tasks
X-User-Id: {{USER_ID}}
```
**Expected:** Mock tasks from BFF memory (check INBOX_MOCK_ENABLED=true)

---

## 🔐 LEVEL 5: CORS

### ✅ 11. Preflight Request (OPTIONS)
```
OPTIONS http://localhost:8086/api/workspace/tasks
Origin: http://localhost:3000
Access-Control-Request-Method: GET
Access-Control-Request-Headers: Authorization
```
**Expected:** 200 OK with CORS headers:
- `Access-Control-Allow-Origin: http://localhost:3000`
- `Access-Control-Allow-Credentials: true`
- `Access-Control-Allow-Methods: GET, POST, PUT, DELETE...`

### ✅ 12. Unauthorized Origin → CORS Denied
```
OPTIONS http://localhost:8086/api/workspace/tasks
Origin: http://evil.com
```
**Expected:** 403 Forbidden (no CORS headers)

---

## 🔐 LEVEL 6: Service Connectivity

### ✅ 13. BFF Health Check
```
GET http://localhost:8087/health
```
**Expected:** 200 OK + `{ status: "UP" }`

### ✅ 14. Gateway → BFF Success
```
GET http://localhost:8086/api/workspace/tasks
Authorization: Bearer {{TOKEN}}
```
**Expected:** 200 OK + response from BFF
**Docker logs:** `docker logs gateway-service | grep BFF`

---

## 🔐 LEVEL 7: End-to-End Flow

### ✅ 15. Complete Auth → Task Flow
```
1. POST http://localhost:8083/api/v1/auth/verify
   ↓ Get userId, role

2. POST http://localhost:8086/auth/login (or equivalent)
   ↓ Get JWT token

3. GET http://localhost:8086/api/workspace/tasks
   Authorization: Bearer {{TOKEN}}
   ↓ Get tasks from Inbox via BFF
```

**Success path:**
- Gateway validates JWT ✅
- Gateway extracts userId from JWT ✅
- Gateway forwards to BFF ✅
- BFF sends X-User-Id/X-Actor-Id to Inbox ✅
- Inbox returns tasks for user ✅

---

## 🔐 LEVEL 8: Error Scenarios

### ✅ 16. Invalid Inbox Credentials
```
POST http://localhost:8083/api/v1/auth/verify
{
  "login": "nonexistent",
  "password": "wrong"
}
```
**Expected:** 401 Unauthorized (same response for all error types)

### ✅ 17. Missing Authorization
```
GET http://localhost:8086/api/workspace/tasks
(no Authorization header)
```
**Expected (auth=true):** 401
**Expected (auth=false):** 200 OK

### ✅ 18. Invalid JSON
```
POST http://localhost:8083/api/v1/auth/verify
{
  "login": "user",
  "password: "missing-quote
}
```
**Expected:** 400 Bad Request

---

## 🔐 LEVEL 9: Security Headers

### ✅ 19. Check Security Headers
```
GET http://localhost:8086/api/workspace/tasks
Authorization: Bearer {{TOKEN}}
```

**Response headers to verify:**
- `X-Content-Type-Options: nosniff` (if configured)
- `X-Frame-Options: DENY` (if configured)
- `Strict-Transport-Security` (if HTTPS configured)

---

## 🔐 LEVEL 10: Docker Health & Logs

### ✅ 20. Services Running
```bash
docker ps -a | grep -E "gateway|bff|inbox"
```
**All should show: STATUS = Up X minutes**

### ✅ 21. Check Logs for Errors
```bash
docker logs gateway-service --tail 50 | grep -i error
docker logs bff-service --tail 50 | grep -i error
docker logs inbox-service --tail 50 | grep -i error
```
**No errors should appear**

### ✅ 22. Network Connectivity (from Docker)
```bash
docker exec gateway-service curl -f http://bff-service:8082/health
docker exec bff-service curl -f http://inbox-service:8080/health
```
**Both should return 200 OK**

---

## Summary Checklist

| # | Test | Status | Notes |
|---|------|--------|-------|
| 1 | Inbox Verify Credentials | ☐ | Base auth |
| 2 | Generate Gateway Token | ☐ | JWT creation |
| 3 | No Token → 401 | ☐ | Auth enforcement |
| 4 | Bearer Token → Success | ☐ | Token acceptance |
| 5 | Cookie Token → Success | ☐ | Cookie extraction |
| 6 | Invalid Token → 401 | ☐ | Signature validation |
| 7 | Expired Token → 401 | ☐ | TTL validation |
| 8 | X-User-Id Forwarding | ☐ | BFF forwarding |
| 9 | Inbox Mock OFF | ☐ | Real Inbox data |
| 10 | Preflight OPTIONS | ☐ | CORS support |
| 11 | Unauthorized Origin | ☐ | CORS enforcement |
| 12 | BFF Health | ☐ | Service up |
| 13 | Gateway → BFF | ☐ | Integration |
| 14 | E2E Auth → Tasks | ☐ | Full flow |
| 15 | Invalid Credentials | ☐ | Error handling |
| 16 | Security Headers | ☐ | Security |
| 17 | Docker Health | ☐ | Infrastructure |
| 18 | Network Connectivity | ☐ | Docker network |
| 19 | No Auth Header | ☐ | Default behavior |
| 20 | Invalid JSON | ☐ | Input validation |
| 21 | Logs Clean | ☐ | No errors |
| 22 | Service Logs | ☐ | Debugging |

---

## 📝 Notes for Postman

1. **Pre-request Script:** Extract token from response
   ```javascript
   var jsonData = pm.response.json();
   pm.environment.set("TOKEN", jsonData.token);
   ```

2. **Test Assertions:** Validate responses
   ```javascript
   pm.test("Status is 200", () => {
       pm.response.to.have.status(200);
   });
   ```

3. **Create Collection** and share across team

---

## 🚀 Quick Start

1. Copy this file into Postman
2. Create folder structure: Auth → Gateway → BFF → Inbox → E2E
3. Add tests to each request
4. Run collection with 10-second delay between requests
5. Generate report

