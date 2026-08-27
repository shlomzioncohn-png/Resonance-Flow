# LENS Shared DTOs Library

**Version:** 1.0.0  
**Owner:** LENS Team  
**Purpose:** Single source of truth for all data transfer objects (DTOs) shared between Gateway, BFF, and Frontend

---

## Overview

The LENS ecosystem has three main services that need to communicate with the same data structures:

```
Frontend
    ↓
Gateway (routes to BFF)
    ↓
BFF (receives/transforms data from team services)
    ↓
Gateway (passes to Frontend)
```

**Problem:** If each service defines DTOs independently, we get:
- ❌ Duplicated code
- ❌ Sync issues (someone forgets to update in both places)
- ❌ Type mismatches between services
- ❌ Frontend doesn't match Backend

**Solution:** Shared DTOs library

---

## Architecture Decision

### Why Shared Library?

| Aspect | Independent DTOs | Shared Library |
|--------|------------------|----------------|
| **Single Source of Truth** | ❌ NO | ✅ YES |
| **Maintenance** | ❌ Duplicate everywhere | ✅ One place |
| **Sync Issues** | ❌ Sync nightmare | ✅ Automatic |
| **Type Safety** | ❌ Inconsistencies | ✅ Guaranteed |
| **Deployment** | ❌ Manual coordination | ✅ Auto-sync |

### Why NOT OpenAPI (for now)?

While OpenAPI is industry-standard and powerful, it adds:
- Learning curve for the team
- Extra build step complexity
- Tool configuration overhead

**Shared DTOs** gives us 80% of the benefits with 20% of the complexity.

---

## Structure

```
teams/lens/shared-dtos/
├── pom.xml                              # Maven build config
├── README.md                            # This file
├── src/main/java/com/rnd/lens/dto/
│   ├── UserDTO.java                     # User model
│   ├── TaskDTO.java                     # Task model
│   ├── DashboardDTO.java                # Dashboard model
│   ├── ProjectDTO.java                  # Project model
│   └── ErrorResponse.java               # Error format (shared)
└── src/main/resources/
    └── application.properties           # (if needed)
```

---

## How It Works

### 1. Define DTOs in shared-dtos

```java
// teams/lens/shared-dtos/src/main/java/com/rnd/lens/dto/UserDTO.java

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private String id;
    private String name;
    private String email;
    private String role;
}
```

### 2. Gateway Imports It

```xml
<!-- gateway/pom.xml -->
<dependency>
    <groupId>com.rnd.lens</groupId>
    <artifactId>shared-dtos</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// gateway/src/main/java/.../controller/DashboardController.java
@GetMapping("/api/dashboard")
public ResponseEntity<DashboardDTO> getDashboard() {
    return ResponseEntity.ok(bffResponse);  // ← DashboardDTO from shared-dtos
}
```

### 3. BFF Imports It

```xml
<!-- bff/pom.xml -->
<dependency>
    <groupId>com.rnd.lens</groupId>
    <artifactId>shared-dtos</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// bff/src/main/java/.../service/DashboardService.java
public DashboardDTO getDashboard() {
    DashboardDTO dto = new DashboardDTO();  // ← Same class everywhere
    // ... populate from team services
    return dto;
}
```

### 4. Frontend Uses It

Frontend generates TypeScript interfaces from the same source:
```typescript
// frontend/src/types/index.ts
export interface DashboardDTO {
  id: string;
  name: string;
  tasks: TaskDTO[];
}
```

---

## When to Update DTOs

**Adding a field:**
1. Update `UserDTO.java` in shared-dtos
2. Rebuild: `mvn clean install` in shared-dtos
3. Gateway & BFF automatically get the new version (on next build)
4. Frontend regenerates types

**Removing a field:**
1. Update `UserDTO.java` in shared-dtos
2. Remove usages in Gateway & BFF
3. Rebuild all
4. Frontend regenerates types

---

## Current DTOs

### ErrorResponse
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private String status = "ERROR";
    private String errorCode;
    private String message;
    private String path;
    private LocalDateTime timestamp;
    private String details;
}
```

**Used by:**
- Gateway: Returns errors to Frontend
- BFF: Sends errors to Gateway
- Frontend: Displays error messages

### Future DTOs (to be added)

- `UserDTO` - Manager/Employee info
- `TaskDTO` - Task models
- `DashboardDTO` - Dashboard data
- `ProjectDTO` - Project information
- `AnalyticsDTO` - Analytics metrics

---

## Building

```bash
# Build shared-dtos
cd teams/lens/shared-dtos
mvn clean install

# This creates: target/shared-dtos-1.0.0.jar
# Installed in local Maven repo
```

## Adding to Other Services

Once built, add to other services:

```xml
<!-- In gateway/pom.xml or bff/pom.xml -->
<dependency>
    <groupId>com.rnd.lens</groupId>
    <artifactId>shared-dtos</artifactId>
    <version>1.0.0</version>
</dependency>
```

Then rebuild:
```bash
mvn clean install
```

---

## Benefits

✅ **Single Source of Truth**
- One place to define data structure
- No sync issues

✅ **Type Safety**
- Java compile-time checking
- Frontend gets same types (via generation)

✅ **Easy Maintenance**
- Change once, propagate everywhere
- No manual coordination

✅ **Clear Contracts**
- Frontend knows exactly what Gateway returns
- Gateway knows exactly what BFF returns

✅ **Scalability**
- Easy to add new DTOs
- Easy to version if needed

---

## Limitations & Future

**Current Approach:**
- ✅ Good for LENS team coordination
- ✅ Works well for Phase 2 & 3

**Future Improvements:**
- Consider OpenAPI if DTOs become very complex
- Consider versioning if breaking changes occur
- Consider multiple DTO versions for backward compatibility

---

## Team Responsibilities

| Team | Responsibility |
|------|-----------------|
| **LENS (You)** | Maintain shared-dtos structure and DTOs |
| **BFF (Sari)** | Use shared-dtos, add new DTOs as needed |
| **Gateway (You)** | Import shared-dtos, use in controllers |
| **Frontend** | Generate TypeScript from shared-dtos |

---

## References

- [Maven Multi-Module Projects](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Lombok Documentation](https://projectlombok.org/)
- [Spring Data Transfer Objects](https://spring.io/guides)

---

**Questions?** Reach out to the LENS team.
