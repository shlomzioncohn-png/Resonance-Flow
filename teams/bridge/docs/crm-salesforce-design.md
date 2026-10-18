# Design note: Salesforce as a second live CRM source

Companion to `teams/bridge/crm/repository/hubspot/` — adds Salesforce Opportunities as a second
live `DealRepository` implementation, alongside the existing JSON fixture and HubSpot sources.
Design only: no code in this doc, and nothing here builds a webhook endpoint, a `@KafkaListener`,
or any other event listener. `fetchDeal` (already implemented for JSON/HubSpot — see
`teams/bridge/CLAUDE.md` once that section lands) exists to prepare a *future* real-time path for
both CRMs; §7 below sketches what that future path could look like for Salesforce specifically,
as design only.

Every Salesforce fact below is cited against an official Salesforce doc (`developer.salesforce.com`
or `help.salesforce.com`), fetched live while writing this note. A few pages returned a loading
shell to automated fetching and had to be corroborated through the same official pages' own
excerpted/cached text and reputable third-party Salesforce-specialist write-ups (SalesforceBen,
SimplySfdc) instead of a direct quote — those are marked **(secondary-sourced)** below. Anything
I couldn't pin down either way is marked **UNVERIFIED**.

## 1. Auth

**OAuth 2.0 Client Credentials Flow**, documented here primarily against an **External Client
App** — Salesforce's current app framework, and the one Client Credentials Flow setup is being
steered toward (support for External Client Apps shipped in release 248 / Spring '24). The legacy
**Connected App** flow is kept as the alternative, since older orgs may not have External Client
Apps available yet. Both expose the same OAuth behavior; only the admin setup screen differs.
Server-to-server, no interactive login — "useful when you want to directly share information
between two applications without a user getting in the way" and explicitly positioned as "a more
secure alternative to the OAuth 2.0 username-password flow" (which Salesforce is retiring
org-by-org).
[Source](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_client_credentials_flow.htm&language=en_US&type=5)

**Org admin setup:**
- External Client App (primary):
  [setup guide](https://help.salesforce.com/s/articleView?id=sf.meta_configure_client_credentials_flow_for_external_client_apps.htm&language=en_US&type=5).
- Connected App (alternative, legacy):
  [setup guide](https://help.salesforce.com/s/articleView?language=en_US&id=sf.connected_app_client_credentials_setup.htm&type=5).
- Either way: "Enable Client Credentials Flow" turned on, plus an **integration/"running" user**
  mapped to the app — this flow has no interactive login, so every API call runs as whichever user
  is configured here; that user's profile/permission set governs what the app can see (e.g. which
  Opportunities are visible) — and the app's consumer key + consumer secret.

No Salesforce org exists for this work yet (§9) — which of the two app types is actually available
depends on the eventual target org's edition/release, so both are documented rather than guessing.

**Token request:**
```
POST https://{MyDomain}.my.salesforce.com/services/oauth2/token
grant_type=client_credentials
client_id={consumer key}
client_secret={consumer secret}
```
`login.salesforce.com`/`test.salesforce.com` are explicitly **not** supported for this flow — it
must go through the org's My Domain URL.
[Source](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_client_credentials_flow.htm&language=en_US&type=5)

**Response:** `access_token`, `instance_url` (the base URL for every subsequent API call —
not necessarily the same host as the token endpoint), `id`, `token_type: "Bearer"`, `scope`,
`issued_at`, `signature`. Same source.

**Env vars** (mirroring `HUBSPOT_TOKEN`'s no-default, fail-loud convention):
`SALESFORCE_CLIENT_ID`, `SALESFORCE_CLIENT_SECRET`, `SALESFORCE_LOGIN_URL` (the My Domain base
URL, e.g. `https://my-org.my.salesforce.com`).

**Token expiry/refresh — the important asymmetry with HubSpot:** this flow issues **no refresh
token** ("This flow doesn't support refresh tokens" — same source), and the token response above
carries **no `expires_in`** either — there's nothing in the response to compute a TTL from.
Validity is governed entirely by the connected app's own session-timeout policy, which
`SalesforceClient` has no API to read.

**Design consequence (confirmed):** `SalesforceClient` cannot do proactive, TTL-based refresh the
way a typical OAuth client would. It caches the token and refreshes **reactively**: on a 401
(`INVALID_SESSION_ID`) from any API call, it discards the cached token, requests exactly one fresh
one, and retries that one call once. A second 401 after that fresh token is fatal — it means the
credentials themselves are bad, not just an expired session, so retrying further would only loop.
This is a deliberate, confirmed behavioral difference from `HubSpotDealRepository`, where 401 is
unconditionally fatal: HubSpot's bearer token is a long-lived private-app token with no session
concept, so a 401 there really does mean "bad token," whereas Salesforce's session-based tokens
are expected to go stale in the course of normal operation.

**JWT Bearer Flow** — documented as the **production alternative**, not merely a fallback. It
avoids storing a client secret in Bridge's own config in favor of a private key, at the cost of
needing an X.509 certificate (≤4 KB) uploaded to the connected/external client app and an admin
pre-authorization decision per profile/permission set. Also issues **no refresh token** and needs
a fresh signed JWT per token request — same reactive-refresh-on-401 design applies.
[Source](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_jwt_flow.htm&language=en_US&type=5)
Worth it for a production deployment that wants cert-based auth instead of a long-lived shared
secret sitting in config/Kubernetes secrets.

## 2. Data

**API version:** `v68.0` ("Winter '27", the current release as of this writing — Salesforce ships
three releases a year and retires old API versions, so this should be a config value, not
hardcoded, the same way HubSpot's `base-url` is).
[Source](https://resources.docs.salesforce.com/latest/latest/en-us/sfdc/pdf/api_rest.pdf) (REST
API Developer Guide, cover page: "Version 68.0, Winter '27").

**One generic client method, not three.** Unlike HubSpot (a different REST endpoint and response
shape for pipelines vs. the deals list vs. one deal), every Salesforce read here is the exact same
`query` resource with a different SOQL string. `SalesforceClient` exposes one
`List<Map<String, Object>> query(String soql)` — authenticate, run the query, follow
`nextRecordsUrl` to completion, return every record — and `SalesforceDealRepository` (B3) supplies
whichever SOQL string a given read needs. There's no dedicated "single-record" client method the
way `HubSpotClient.fetchDeal` is dedicated to one endpoint; a `WHERE Id = '...'` query is just
another string through the same `query()` path, and naturally never has a second page.

**Default: current state only, one `Deal` per Opportunity — no `OpportunityHistory` read.**
```sql
SELECT Id, Name, Description, StageName, Amount, CloseDate, CreatedDate, LastStageChangeDate,
       LastModifiedDate, Owner.Name, Owner.Email
FROM Opportunity
ORDER BY Id
```
via `GET /services/data/v68.0/query/?q={URL-encoded SOQL}`, paginated (below). `fetchDeal` uses the
same shape with `WHERE Id = '{OpportunityId}'` instead of `ORDER BY Id` — and needs nothing else:
no second query, no 404 branch. A SOQL query for a nonexistent Id simply returns
`totalSize: 0, records: []`; "not found" is just "zero records," the same check already used
everywhere else in this design. This is simpler than HubSpot's `fetchDeal`, which needs a
dedicated 404 branch in `HubSpotClient.attemptOptional`.

This does **not** violate `DealRepository`'s "append-only stage events, a deal id *can* appear
more than once" contract (`DealRepository`'s own Javadoc) — it just means Salesforce's default
history-per-deal-id is always exactly one row long, the current state. `JsonDealRepository`/
`HubSpotDealRepository` already produce single-row histories today for any deal with no recorded
transitions yet; this is that same case, just the only case, by design, for now.

**`stageChangedAt` source — `LastStageChangeDate`, with a per-row `CreatedDate` fallback.**
**Verified 2026-10-14 against a real Salesforce Developer Edition org (API v68.0):**
`LastStageChangeDate` exists and is set whenever an Opportunity's stage changes - but it is
`null` for an Opportunity that has never changed stage (true for all 20 seeded records in that
org). Before this was caught, `SalesforceDealRepository.toDeal()` read only this field, so
`stageChangedAt` came back `null` for every brand-new deal, `Deal`'s constructor's
`requireNonNull` threw, and the record was silently skipped (`skippedAtRead`) - every brand-new
deal was dropped. **Fix:** when the configured field is null/blank on a row, `toDeal()` falls back
to that row's `CreatedDate` - a deal that has never changed stage has been in its current stage
since it was created. **Not** `LastModifiedDate`: that field bumps on any field edit, not just a
stage change, and using it as the fallback would break stalled-deal detection for a deal that was
edited but never moved. If `CreatedDate` is also missing, the row is still skipped and counted in
`skippedAtRead`, same as before this fix. This is unrelated to the separate, still-available
escape hatch of switching `bridge.crm.salesforce.stage-changed-at-field` /
`SALESFORCE_STAGE_CHANGED_AT_FIELD` to `LastModifiedDate` wholesale, for a deployer who finds the
configured field itself absent from the org's schema entirely (not the case verified here).

**Full history — opt-in, off by default, for future features.** Reading every stage transition
(mirroring HubSpot's/JSON's append-only model) is still a real, implementable mode — gated behind
`bridge.crm.salesforce.load-history: false` (`application-salesforce.yml`) rather than built as the
default. When `true`:
```sql
SELECT OpportunityId, StageName, Amount, CloseDate, CreatedDate
FROM OpportunityHistory
ORDER BY OpportunityId, CreatedDate ASC
```
read as a **full scan**, not `WHERE OpportunityId IN (...)` — SOQL's `IN` clause caps out at
**500 values**, with `MALFORMED_QUERY` beyond that
[Source](https://www.salesforceben.com/salesforce-soql-queries-and-limits/) **(secondary-sourced)**,
unsafe to assume away for an arbitrary deployment. A full scan costs reading history rows for every
Opportunity on every `snapshot()` call — the same "every call is a fresh, independent fetch" cost
HubSpot already pays (`HubSpotDealRepository`'s Javadoc, "Freshness") — grouped client-side by
`OpportunityId`, same pattern `JsonDealRepository`/`HubSpotDealRepository` already use over
`Deal::dealId`. `fetchDeal` under this flag adds `WHERE OpportunityId = '{OpportunityId}'
ORDER BY CreatedDate ASC` to get that one deal's full history instead of just its current row.

**The label-rename risk, specific to the history mode.** `OpportunityHistory.StageName` is a
label *snapshot at the time that row was written*, not a stable id — if an admin later renames a
stage's label in `OpportunityStage` (e.g. "Needs Analysis" → "Discovery"), an **old** history row
still says "Needs Analysis," which no longer matches any *current* `OpportunityStage.MasterLabel`
(§3's mapping is keyed by label match against the live `OpportunityStage` list fetched that same
call). That historical row's stage can no longer be resolved and becomes a per-record skip
(`skippedAtRead`) — not a whole-`snapshot()` failure, but real data loss for that one row. This
risk **does not exist in the default current-state-only mode**: a live `Opportunity.StageName`
always matches some current `OpportunityStage.MasterLabel`, by Salesforce's own invariant (you
cannot set a stage to a label that isn't a valid stage). It only bites once `load-history` reads
stage labels that were true in the past, not now — worth knowing before flipping that flag on an
org with a history of stage renames.

**Pagination:** the query resource returns up to 2,000 records per page; if more remain, the
response carries `done: false` and a `nextRecordsUrl` to `GET` (no new params) for the next batch,
until `done: true`. [Source](https://developer.salesforce.com/docs/platform/api-rest/guide/dome-query.html)
(confirmed via the page's own indexed text — direct fetch of this page returned a loading shell,
corroborated by a second independent query against the same documented behavior.)

## 3. Mapping Salesforce → `Deal`

| `Deal` field | Source | Notes |
|---|---|---|
| `dealId` | `Opportunity.Id` | 18-char Salesforce record Id |
| `title` | `Opportunity.Name` | |
| `description` | `Opportunity.Description` | nullable long-text field |
| `stage` | derived — see stage-mapping table below | resolved from `Opportunity.StageName` (default) or per `OpportunityHistory` row (`load-history: true`) |
| `ownerEmail` | `Opportunity.Owner.Email` | SOQL parent-relationship traversal (`Owner` is the standard relationship name for `OwnerId`) |
| `ownerName` | `Opportunity.Owner.Name` | |
| `closeDate` | `Opportunity.CloseDate` | already a `Date` — no epoch-millis string parsing needed, unlike HubSpot's `closedate` property |
| `dealValue` | `Opportunity.Amount` | nullable `Currency` |
| `currency` | left `null` | `CurrencyIsoCode` is **not queried at all** — it only exists when the org has Multi-Currency enabled, and querying it on a single-currency org fails the whole query with `INVALID_FIELD`. Not worth a config flag for a field nothing downstream reads yet; matches HubSpot's current always-null behavior for this field |
| `crmAccountSlug` | left `null` | same as `HubSpotDealRepository` — nothing downstream reads it; `DealToTaskMapper` only reads `stage`/`closeDate`/`title`/`dealId` per `HubSpotDealRepository`'s own class Javadoc |
| `sourceUrl` | `{instance_url}/{OpportunityId}` | classic-style record URL; Salesforce transparently redirects it to the Lightning URL |
| `createdAt` | `Opportunity.CreatedDate` | |
| `stageChangedAt` | `Opportunity.LastStageChangeDate`, with a `CreatedDate` fallback when that field is null/blank | see §2 for the 2026-10-14 live verification and the fallback rule (never `LastModifiedDate`). Under `load-history: true`, this instead comes from the specific `OpportunityHistory.CreatedDate` row, same as HubSpot's per-history-entry timestamp |

**Stage mapping — metadata-based, not label-based**, to avoid `HubSpotStageMapping`'s exact-label
weakness (audit finding F6: a renamed or custom stage label fails the whole sync). `OpportunityStage`
is a standard, read-only, queryable metadata object — one row per stage of the org's sales
process — with fields `ApiName`/`MasterLabel` (the label), `IsActive`, `IsClosed`, `IsWon`,
`DefaultProbability` (0–100), and `SortOrder` **(secondary-sourced** — corroborated by the object
reference page's own indexed field list; direct fetch returned a loading shell**)**.

**Update 2026-10-14: `HubSpotStageMapping` got the same fix.** This section originally described
Salesforce's metadata-based mapping as the fix for F6, contrasted against HubSpot's then-current
exact-label matching. HubSpot's own stage resolution is metadata-based now too (`isClosed`/
`probability`, no `IsWon`-equivalent flag so a closed stage's won/lost call comes from
`probability` being exactly `0`/`1` instead) — see `teams/bridge/CLAUDE.md` §11 for the full
policy table. The rest of this section's F6 rationale is unchanged and still describes Salesforce
accurately; it just isn't the *only* CRM with the fix anymore.

Proposed mapping, resolved fresh every `snapshot()`/`fetchDeal()` call (mirrors
`HubSpotStageMapping.resolveIdsToStages` being re-fetched every call, not cached):

| `OpportunityStage` metadata | → `DealStage` |
|---|---|
| `IsClosed = true, IsWon = true` | `CLOSED_WON` |
| `IsClosed = true, IsWon = false` | `CLOSED_LOST` |
| `IsClosed = false`, `DefaultProbability` 0–20 | `PROSPECT` |
| `IsClosed = false`, `DefaultProbability` 21–50 | `QUALIFIED` |
| `IsClosed = false`, `DefaultProbability` 51–80 | `PROPOSAL` |
| `IsClosed = false`, `DefaultProbability` 81–99 | `NEGOTIATION` |
| `IsClosed = false`, `DefaultProbability` is `null` | `PROSPECT` (most conservative open bucket) |

The four band boundaries (20/50/80/99) are **configurable** in `application-salesforce.yml`
(`bridge.crm.salesforce.stage-bands.{qualified,proposal,negotiation}-min`), not hardcoded — these
are Bridge's own policy, not a Salesforce convention, and the right split may need tuning per org.

`DealStage.ON_HOLD` has no Salesforce equivalent, same as HubSpot — never produced.

**No startup validation, on purpose — "never fail startup over stage metadata."** Unlike
`HubSpotDealRepository`'s constructor, which eagerly fetches the pipeline once purely to fail fast
on an unrecognized stage *label*, `SalesforceDealRepository`'s constructor does **not** eagerly
fetch `OpportunityStage` or validate anything about it. There is nothing for it to validate against
up front: every stage, recognized or brand new, carries `IsClosed`/`IsWon`/`DefaultProbability`
metadata by construction, so there is no "unrecognized stage" state for a startup check to catch.
An eager fetch here would just be an extra API call with no corresponding safety benefit.

**Unknown/custom stage behavior — this is the actual fix for F6's weakness, not just a rename:**
because the mapping reads `IsClosed`/`IsWon`/`DefaultProbability` off *every* stage's own metadata
rather than matching a hardcoded label string, a brand-new or renamed custom stage is classified
correctly automatically — there is no equivalent to `UnknownHubSpotStageException`, and no
"pipeline fetch fails entirely because one stage has an unrecognized label" failure mode at all.
The only genuinely unmapped case is a `StageName` on an `Opportunity`/`OpportunityHistory` row that
doesn't appear in the fetched `OpportunityStage` list at all (e.g. a stage deleted mid-flight,
data corruption) — that's a **per-record skip**, counted in `skippedAtRead`, exactly like an
unrecognized HubSpot stage *id* (not label) is today. The probability bands above are Bridge's own
policy choice, not a Salesforce-documented convention, kept as the default and made configurable
(§3) rather than hardcoded.

## 4. `sourceRef`

`"crm:salesforce:<OpportunityId>"` — distinct from HubSpot's `"crm:<id>"` (unchanged). `SourceType`
stays `CRM_DEAL` for both; nothing downstream (`DealToTaskMapper`, Inbox's dedup key) needs a new
source type, since `sourceRef` alone already disambiguates HubSpot deals from Salesforce
Opportunities even if the two systems ever reused the same raw id.

## 5. Robustness

Same shape as `HubSpotClient` — connect/read timeouts, exponential backoff with a bounded retry
budget, transport failures recognized by walking the cause chain for `IOException` — with
Salesforce's real error behavior substituted where it genuinely differs:

- **No HTTP 429. `403` + `REQUEST_LIMIT_EXCEEDED` is fatal, not retried.** Salesforce signals a
  rate-limit/overage with **`403`** and a JSON error body shaped
  `[{"message": "...", "errorCode": "REQUEST_LIMIT_EXCEEDED"}]` — the same array-of-object error
  shape the REST API uses for every error response
  [Source](https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/errorcodes.htm)
  (corroborated via indexed text; direct fetch 403'd). **Deliberately not retried**, unlike
  HubSpot's 429: the underlying limit is a **24-hour rolling window**, not a short per-second
  bucket, so backing off for a few seconds and retrying only burns additional calls against a
  budget that won't reset for hours — it cannot possibly succeed sooner. `SalesforceClient` fails
  immediately with a clear `SalesforceClientException` ("Salesforce API request limit exceeded"),
  parsed from the body's `errorCode` (checked specifically on a `403`, since `403` is otherwise a
  fatal permissions error for a different reason). The same `errorCode` also covers a **separate,
  narrower concurrent-long-running-request cap** (e.g. 25 concurrent long-running requests on a
  production org) — same signal, different underlying cause; no special handling for that case
  specifically, per the instruction not to build one.
- **`401` (`INVALID_SESSION_ID`)** — per §1, this means "the cached token is no longer valid," not
  necessarily "the credentials are bad." Refresh the token once and retry the triggering call once;
  a second `401` after a genuinely fresh token is fatal.
- **`500`/`502`/`503`/`504`** — transient server errors, retried with exponential backoff, same
  tier as HubSpot's 502/503/504.
- **`400`/`405`/`409`**, and any `401` that survives a fresh token — fatal, not retried. (There is
  no meaningful Salesforce `404` for the query-based reads this design uses — see §2's "not found
  is zero records" note — so unlike HubSpot there's no fatal-404 case to test for `fetchPipelines`-
  style calls; `fetchDeal`'s "not found" path never reaches the error-handling layer at all.)
- The token (and client secret) are never logged or included in any `SalesforceClientException`
  message, mirroring `HubSpotClientException`'s rule.
- A malformed `OpportunityHistory` row, or (per §3) a `StageName` absent from the resolved
  `OpportunityStage` metadata, is a per-record skip counted in `skippedAtRead` — never aborts the
  whole `snapshot()`.

For context on real-world limits worth testing against: a free Developer Edition org's daily API
budget is **15,000 requests over a rolling 24-hour window**, with no overage allowance (unlike paid
orgs) [Source](https://developer.salesforce.com/docs/platform/api/guide/implementation-considerations.html)
**(secondary-sourced** — corroborated via indexed text, same reason as above**)**. Comfortably
enough for `mvn test`'s MockWebServer suite (which never touches a real org) and for the optional
manual check in §8, but worth knowing before assuming a tight retry loop against a live sandbox is
free.

## 6. Profiles

Same convention as `HubSpotDealRepository` (`teams/bridge/CLAUDE.md` §5): `SalesforceDealRepository`
is `@Repository @Profile("salesforce") @Primary`. One CRM profile is expected to be active per
deployment (`hubspot` XOR `salesforce`, mirroring the doc's "one CRM per deployment" framing).

**Startup check for both active:** Spring would likely already fail context startup if `hubspot`
and `salesforce` were both active, since two `@Primary` beans would exist for `DealRepository` —
but that failure (`NoUniqueBeanDefinitionException`) names Spring's own bean machinery, not the
actual mistake. Add an explicit, CRM-domain-specific check — a small bean (same shape as
`CrmLaneConfig.requireValidTenantIdForKafka`) that reads `Environment.matchesProfiles("hubspot")`
and `Environment.matchesProfiles("salesforce")` and throws a clear `IllegalStateException` naming
both profiles if both are true — so the failure message is actionable instead of a generic Spring
ambiguity error.

## 7. Future real-time (design only — no implementation)

Three Salesforce mechanisms could notify Bridge of Opportunity changes instead of polling
`snapshot()` on a schedule:

- **Change Data Capture (CDC)** — the best fit. Enabling it for the `Opportunity` object is a
  Setup checkbox, no Apex required; Salesforce then auto-publishes an event on the
  `/data/OpportunityChangeEvent` channel for every create/update/delete, consumed via the
  gRPC/Avro **Pub/Sub API**
  [Source](https://developer.salesforce.com/docs/platform/change-data-capture/guide/cdc-subscribe-pubsub-api.html).
- **Platform Events** — more flexible (a custom event schema), but requires the org's admin to
  write and maintain an Apex trigger or Flow that explicitly publishes an event on every relevant
  change — more moving parts on the Salesforce side than CDC, which needs none.
  [Source](https://developer.salesforce.com/docs/platform/pub-sub-api/guide/qs-subscribe.html)
- **Outbound Messages** — legacy SOAP, needs a Workflow Rule/Flow configured to POST to a publicly
  reachable Bridge endpoint. Least attractive: older tech, and it reintroduces exactly the
  "Bridge needs a public webhook endpoint" requirement this task is explicitly not building yet.

**How this would plug into `fetchDeal`:** a CDC event only carries the *changed* fields and is
at-least-once/eventually-consistent — the design pattern is to treat the event as a cheap trigger,
not a source of truth, and call `SalesforceDealRepository.fetchDeal(event.getRecordId())`
immediately after to get the authoritative current state before doing anything with it. Same
pattern HubSpot's own future webhook path would use `fetchDeal` for.

## 8. Testing plan

- **`SalesforceClientTest`** (MockWebServer, no live calls — same library already used by
  `HubSpotClientTest`/`HubSpotDealRepositoryTest`): mock the token endpoint and the query endpoint
  separately. Cases: auth success; auth failure (bad credentials → fatal); a cached token reused
  across calls; a `401` mid-query triggering exactly one token refresh + retry, then success;
  a second `401` after the refreshed token failing fatally; 2+ pages via `nextRecordsUrl`; a `403`
  with `REQUEST_LIMIT_EXCEEDED` failing immediately with **no retry**; `500`/`503` retried; a
  fatal `400`; a connect/read timeout; and — across every case — the client secret and access
  token never appearing in any exception message.
- **`SalesforceStageMappingTest`**: won (`IsClosed=true,IsWon=true`), lost
  (`IsClosed=true,IsWon=false`), each open probability band (using the configured, not hardcoded,
  boundaries), a renamed label at an unchanged probability (proves label-independence — the actual
  F6 fix), a custom stage with `DefaultProbability = null`.
- **`SalesforceDealRepositoryTest`** (default mode — current state only): `snapshot()` freshness
  (no cache field, no constructor-time `OpportunityStage` fetch), one `Deal` per Opportunity,
  `fetchDeal` cases mirroring Part A's HubSpot tests (200 → correct `Deal`, "not found" → empty via
  a zero-record result rather than a 404, two calls never reusing a cached result, the
  `LastModifiedDate` fallback when `LastStageChangeDate` isn't queried/available). **Resolved
  2026-10-14:** this bullet originally anticipated a `LastModifiedDate` fallback test; the live
  verification against a real org found the actual gap was `LastStageChangeDate` itself coming
  back `null` for never-changed deals, not missing entirely - the real fix and its tests are a
  per-row `CreatedDate` fallback (§2), covered by `SalesforceDealRepositoryTest`'s dedicated
  fallback cases and one `CrmSalesforceLaneIntegrationTest` stalled-bump case.
- **`SalesforceDealRepositoryTest` (`load-history: true`)**: oldest-first history per
  `OpportunityId`, `latestPerDeal()`, `skippedAtRead` on a bad/unresolvable history row, and
  specifically the label-rename case from §2 (an old history row's label no longer matches any
  current `OpportunityStage` entry → skipped, not fatal).
- **Integration-style (`B5`)**: `CrmLane` wired with `SalesforceDealRepository` (MockWebServer) →
  `DealToTaskMapper` → `CrmTaskValidator` → a capturing publisher — one `Task` per Opportunity, the
  latest stage, the right status/urgency/deadline, `sourceRef` format.
- **Optional manual check**: a free Salesforce Developer Edition org — create an External
  Client App/Connected App with Client Credentials Flow enabled, a couple of test Opportunities
  with a few stage changes each, run Bridge with `SPRING_PROFILES_ACTIVE=salesforce` pointed at
  it, hit `POST /bridge/crm/sync`, and eyeball the logged `Task`s.

## 9. Open questions for you

Resolved by review: auth (Client Credentials Flow, External Client App primary / Connected App
alternative, JWT Bearer as the production alternative), 401 handling (one reactive refresh +
retry, confirmed), `REQUEST_LIMIT_EXCEEDED` (fatal, not retried), history default (current-state
only, `load-history` opt-in), currency (always `null`, no flag), stage bands (keep the proposed
defaults, made configurable). What's left:

1. **No Salesforce org exists for this work yet** — confirmed, a free Developer Edition is enough
   for the optional manual check in §8 and for confirming `LastStageChangeDate` actually exists
   (§2) before this ships anywhere real. Nothing to decide now; flagging so it isn't forgotten
   once `SalesforceClient`/`SalesforceDealRepository` exist and need something to run against.
   **Resolved 2026-10-14:** a free Developer Edition org was provisioned and used for exactly this
   check - see §2.
2. Once an org is provisioned: confirm whether it has an External Client App or only legacy
   Connected Apps available (§1), and whether `LastStageChangeDate` is really present on that org's
   Opportunity object (§2) — both affect setup instructions and the `stageChangedAt` fallback path,
   respectively, but neither blocks writing `SalesforceClient`/`SalesforceDealRepository` now.
   **Resolved 2026-10-14, the `LastStageChangeDate` half:** the field exists and is populated on
   stage change, but is `null` on a never-changed Opportunity - not the "doesn't exist at all"
   case this question anticipated. See §2 for the actual fallback fix this led to. The External
   Client App vs. Connected App half is still open - not checked as part of this fix.
