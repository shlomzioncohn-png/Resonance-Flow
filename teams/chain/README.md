# Chain Service API Contract

## Overview

The **Chain** service manages task relationships and readiness information used by the Resonance Flow system.

Chain is responsible for:
- Task dependencies and dependents
- Direct blockers
- Task hierarchy traversal
- Task constraints
- Task eligibility/readiness
- Scheduling state updates published to other services

Task creation is not owned by Chain. Canonical tasks and task IDs are owned by Inbox.

All task identifiers are represented as UUID values.

## Base Path

```text
/tasks
```

API versioning is not currently included in the controller path.

# Dependency Management

## Add Dependency

```http
POST /tasks/{taskId}/dependencies
```

Creates a dependency for the specified task.

If task A depends on task B:
- `taskId` represents task A
- `dependsOnTaskId` represents task B

### Request Body

```json
{
  "dependsOnTaskId": "11111111-1111-1111-1111-111111111112"
}
```

### Responses

- `201 Created` — dependency created successfully
- `400 Bad Request` — invalid request or invalid dependency
- `404 Not Found` — referenced task was not found
- `409 Conflict` — dependency already exists

## Remove Dependency

```http
DELETE /tasks/{taskId}/dependencies/{dependsOnTaskId}
```

Removes an existing dependency.

### Responses

- `204 No Content` — dependency removed successfully
- `404 Not Found` — task or dependency was not found

## Get Dependencies

```http
GET /tasks/{taskId}/dependencies
```

Returns dependency relationships for the specified task.

### Responses

- `200 OK`
- `404 Not Found`

If no dependencies exist:

```json
[]
```

## Get Dependents

```http
GET /tasks/{taskId}/dependents
```

Returns dependency relationships representing tasks that depend on the specified task.

### Responses

- `200 OK`
- `404 Not Found`

If no dependents exist:

```json
[]
```

# Dependency and Hierarchy Traversal

## Get Direct Blockers

```http
GET /tasks/{taskId}/blockers
```

Returns direct task objects that block the specified task.

Example:

```text
A depends on B
A depends on C
```

A request for blockers of A returns B and C.

### Responses

- `200 OK`
- `404 Not Found`
- `400 Bad Request` — invalid task ID

## Get Ancestors

```http
GET /tasks/{taskId}/ancestors
```

Returns ancestor tasks in the hierarchy.

Traversal results should not contain duplicate nodes.

### Responses

- `200 OK`
- `404 Not Found`
- `400 Bad Request` — invalid task ID

## Get Descendants

```http
GET /tasks/{taskId}/descendants
```

Returns descendant tasks in the hierarchy.

Traversal results should not contain duplicate nodes.

### Responses

- `200 OK`
- `404 Not Found`
- `400 Bad Request` — invalid task ID

# Constraint Management

Supported constraint types:

```text
APPROVAL_NEEDED
BLOCKED_BY_INFO
DATE_WINDOW
```

Supported constraint statuses:

```text
PENDING
SATISFIED
WAIVED
```

## Create Constraint

```http
POST /tasks/{taskId}/constraints
```

Creates a constraint associated with a task.

### Example Request

```json
{
  "type": "APPROVAL_NEEDED",
  "reason": {
    "approver": "manager"
  },
  "sourceRef": "email-123"
}
```

### Responses

- `201 Created`
- `400 Bad Request` — invalid constraint or request body
- `404 Not Found` — task not found

## Get Task Constraints

```http
GET /tasks/{taskId}/constraints
```

Returns all constraints associated with the specified task.

### Responses

- `200 OK`
- `404 Not Found`

If no constraints exist:

```json
[]
```

## Update Constraint Status

```http
PATCH /tasks/{taskId}/constraints/{constraintId}
```

Updates the status of an existing constraint.

### Example Request

```json
{
  "status": "SATISFIED"
}
```

### Responses

- `200 OK`
- `400 Bad Request` — invalid or missing status
- `404 Not Found` — task or constraint not found

# Task Eligibility

## Get Eligibility

```http
GET /tasks/{taskId}/eligibility
```

Returns the current readiness state of a task.

### Example Response

```json
{
  "taskId": "11111111-1111-1111-1111-111111111111",
  "isReady": true,
  "blockingCount": 0,
  "openConstraintCount": 0
}
```

A task is ready when:

```text
blockingCount == 0
AND
openConstraintCount == 0
```

### Responses

- `200 OK`
- `404 Not Found`
- `400 Bad Request` — invalid task ID

# Error Handling

The API uses centralized exception handling.

Typical status mappings:

| Condition | HTTP Status |
|---|---:|
| Invalid request | 400 |
| Invalid task ID | 400 |
| Invalid dependency | 400 |
| Invalid constraint | 400 |
| Task not found | 404 |
| Dependency not found | 404 |
| Constraint not found | 404 |
| Duplicate dependency | 409 |

# Scheduling Update Event

Chain publishes scheduling state changes asynchronously.

Working event name:

```text
task.scheduling.updated
```

### Event Payload

```json
{
  "task_id": "11111111-1111-1111-1111-111111111111",
  "is_available": true,
  "priority_score": 120
}
```

### Fields

| Field | Description |
|---|---|
| `task_id` | UUID of the task |
| `is_available` | Whether the task is currently available/ready |
| `priority_score` | Current priority score, subject to the final integration contract |

### Flow

```text
Chain -> Kafka -> Inbox
```

Chain calculates task availability. Inbox stores/materializes the corresponding availability state on the canonical task.

The exact physical Kafka topic name, final event schema, consumer implementation, and final priority score ownership should follow the agreed integration contract.

# Validation

The project uses Spring Boot 2.7.x and therefore uses `javax.validation`.

Examples:

```java
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
```

Validation requires:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Invalid request payloads should return `400 Bad Request`.

# Testing

Controller tests belong under:

```text
src/test/java/com/rnd/chain/controller/
```

The controller tests use:
- `@WebMvcTest`
- `MockMvc`
- mocked service dependencies

They verify the REST API layer, including:
- HTTP methods
- endpoint mappings
- status codes
- validation
- JSON responses
- exception-to-HTTP mapping
- empty result handling

Business logic implemented by services should be tested separately.

# Ownership Boundaries

Chain owns task relationship and readiness logic.

Chain does not own canonical task creation.

The API layer should remain thin and delegate business logic to the appropriate service layer.

The final implementation must use the real shared models and service contracts available in the repository after team integration.
