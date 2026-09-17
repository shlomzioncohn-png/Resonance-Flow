# Code Review Skill

## What this skill does
When activated, performs a thorough code review on the specified file or set of files.

## How to use
Say: "תעשה לי code review על [קובץ/תיקייה]" or "review the code in [file/folder]"

## Review checklist

### Correctness
- Logic errors or off-by-one mistakes
- Null pointer risks
- Unhandled edge cases
- Wrong HTTP status codes returned

### Error handling
- All exception types caught and mapped to correct HTTP status
- No internal details (hostnames, stack traces) leaked to client
- Typed exceptions used instead of generic RuntimeException

### Security
- No sensitive data in logs or responses
- No client-supplied values trusted for identity (IDOR)
- Input validation present on all request fields

### Design & architecture
- Responsibilities match the layer (Gateway routes, BFF aggregates, etc.)
- No hardcoded URLs or magic strings — use @Value / application.yml
- DTOs belong to the correct module (each sub-project owns its own)

### Code quality
- Methods are focused and not too long
- Variable and method names are clear
- No dead code or commented-out blocks
- TODO comments are specific and actionable

### Spring Boot specifics
- @Value fields have sensible defaults
- RestTemplate / WebClient has timeouts configured
- @Valid present on request bodies that need validation
- GlobalExceptionHandler covers all thrown exception types

## Output format
Produce the review in this structure:

**Verdict:** Ready / Not ready — one sentence summary

**What's working well** — specific callouts with file:line references

**Issues** — grouped by: Blocker / Should fix / Nice to have
Each issue: location, what the problem is, suggested fix

**Questions for the author** — open decisions or unclear intent
