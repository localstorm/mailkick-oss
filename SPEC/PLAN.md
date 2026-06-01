# MailKick — Implementation Plan

Phases are sequential. Each phase produces a working, testable increment. The Thunderbird extension is deferred to a separate track after the core system is operational.

---

## Phase 1 — Project Skeleton

**Goal:** Maven multi-module project compiles cleanly with all tooling configured.

**Tasks:**
- Create parent `pom.xml` with all module declarations, dependency management (Spring Boot 3.x BOM, AWS SDK v2 BOM, CopyDown, community Anthropic SDK)
- Create empty module stubs: `mailkick-model`, `mailkick-jmap`, `mailkick-rules`, `mailkick-agent`, `mailkick-server`, `mailkick-dashboard`, `mailkick-keygen`, `mailkick-docker`
- Configure Checkstyle and SpotBugs in parent POM
- Configure `mailkick-server` as the Spring Boot executable module
- Add `mailkick-docker` scaffold: `Dockerfile`, `scripts/launch.sh`, `.gitignore` for `download/` and `dist/`
- Add `build.properties.template` and placeholder `build.sh`

**Done when:** `mvn clean package -DskipTests` succeeds across all modules.

---

## Phase 2 — `mailkick-model`

**Goal:** All shared data model classes, DynamoDB repositories, and S3 config utilities defined, tested, and ready for use by other modules.

**Tasks:**

*Data model:*
- `RuleType` enum
- `Rule` — all fields per DDB schema
- `Email` — metadata fields + Markdown body
- `LogEntry` — all fields per DDB schema
- `HealthStatus` — per-component state (`HEALTHY` / `FAILING`) + failure message + failure start time
- `MailKickConfig` — full S3 config structure
- `JsonUtil` — shared Jackson `ObjectMapper`

*DynamoDB utilities:*
- `DdbClientFactory` — creates `DynamoDbClient` from env vars
- `DdbMapper` — model ↔ `AttributeValue` map conversion for `Rule` and `LogEntry`
- `RulesDdbRepository` — `GetItem` (exact + domain), `PutItem`, `DeleteItem`, `Scan`
- `LogDdbRepository` — `PutItem`, `Query` by date, `BatchWriteItem` for bulk delete

*S3 config utilities:*
- `MailKickConfigXml` — XML parsing for `MailKickConfig`
- `MailKickConfigLoader` — reads S3 object, deserialises into `MailKickConfig`
- `MailKickConfigValidator` — validates required fields and internal consistency

**Done when:** all model classes serialise/deserialise correctly; DynamoDB repositories can read and write to real `mailkick.rules` and `mailkick.log` tables; `MailKickConfigLoader` reads a real S3 object and `MailKickConfigValidator` rejects malformed configs.

---

## Phase 3 — `mailkick-jmap` — Core Client

**Goal:** MailKick can authenticate to FastMail, discover the session, and fetch emails from Triage.

**Tasks:**
- `JmapSession` — `GET https://api.fastmail.com/jmap/session`, parses `apiUrl`, `eventSourceUrl`, `primaryAccounts`
- `JmapClient` — sequential HTTP client with `Authorization: Bearer` header, posts JMAP method calls to `apiUrl`
- `MailboxResolver` — `Mailbox/get` to resolve folder names to mailbox IDs (Triage, Inbox, Archive, Spam, Trash)
- `EmailFetcher` — `Email/query` + `Email/get` to fetch emails in Triage with required properties (headers, body parts, `mailboxIds`)
- `EmailMover` — `Email/set` to update `mailboxIds`, set `$seen` / `$flagged` keywords, and `destroy`
- `EmailNormaliser` — extracts metadata from headers, parses `Authentication-Results` for DKIM/SPF/DMARC, converts HTML to Markdown via CopyDown, assembles XML document with CDATA body

**Done when:** a standalone `main()` test can connect to FastMail, list emails in Triage, and print their normalised XML.

---

## Phase 4 — `mailkick-keygen`

**Goal:** Standalone CLI tool for Ed25519 keypair generation.

**Tasks:**
- `KeyGenMain` — generates Ed25519 keypair using `java.security`
- Base64-encode both keys (DER format)
- Print clearly labelled output: public key for Secrets Manager, private key for Thunderbird
- Private key never written to disk

**Done when:** running `java -jar mailkick-keygen.jar` prints a valid keypair; public key can be verified against a message signed with the private key.

---

## Phase 5 — `mailkick-jmap` — Push + Polling

**Goal:** MailKick receives signals when emails arrive in Triage.

**Tasks:**
- `EventSourceListener` — persistent SSE connection to `eventSourceUrl` with `types=Email&closeafter=state&ping=60`; parses `StateChange` events; extracts new email IDs via `Email/changes`; places IDs onto the signal queue; exponential backoff reconnect
- `TriagePoller` — `@Scheduled` every 60 seconds; calls `Email/changes` from last known state string; places new Triage email IDs onto signal queue
- Signal queue — `BlockingQueue<String>` (email IDs); shared between `EventSourceListener`, `TriagePoller`, and the processing worker
- Processing worker — single thread consuming signal queue; checks `mailboxIds` to confirm email is still in Triage; skips if already moved; calls `TriageProcessor` (stubbed for now)

**Done when:** with Triage processor stubbed, signals arrive correctly when test emails are dropped into the Triage folder via the FastMail web UI.

---

## Phase 6 — `mailkick-rules`

**Goal:** DynamoDB rules engine reads and writes `mailkick.rules`.

**Tasks:**
- `RulesRepository` — `GetItem` by exact sender, `GetItem` by domain, `PutItem`, `DeleteItem`, `Scan` (for UI listing)
- `RulesChecker` — exact email lookup → domain lookup → returns `Optional<Rule>`
- `RuleExecutor` — applies matched rule: calls `EmailMover` for `MOVE_TO_FOLDER_NO_PROCESSING`, `SPAM`, `TRASH`; calls `Email/set destroy` for `ERASE`; enqueues email ID for LLM processing for `MOVE_TO_FOLDER_WITH_PROCESSING`

**Done when:** a standalone `main()` test can write a rule to DynamoDB, look it up by sender and domain, and execute it against a test email in Triage.

---

## Phase 7 — `mailkick-agent` — Prompt Loader + LLM Client

**Goal:** MailKick loads the S3 config and can call Anthropic with an email.

**Tasks:**
- `AgentPromptLoader` — loads `MailKickConfig` from S3 on startup; reloads every 5 minutes; tracks last successful load time for health status
- Token estimation — simple character-based estimate (÷4) applied to the XML document before the Anthropic call; compares to `maxEmailSizeTokens`; rejects oversized emails (Inbox + unread + flagged)
- `AnthropicClient` — wraps community SDK; sends system prompt + XML email as user message; declares tool schemas; single-turn call; returns ordered list of tool calls

**Done when:** a standalone `main()` test loads the S3 config, sends a test email XML to Anthropic, and prints the tool calls returned.

---

## Phase 8 — `mailkick-agent` — Tools + Activity Log

**Goal:** All LLM tools implemented and every action logged.

**Tasks:**
- `ToolRegistry` — declares all tools to Anthropic SDK with names, descriptions, input schemas
- Tool implementations:
  - `MoveToFolderTool` — calls `EmailMover`
  - `ArchiveTool` — calls `EmailMover` with Archive folder
  - `SpamTool` — calls `EmailMover` with Spam folder
  - `AddRuleTool` — calls `RulesRepository.put()`; applies to future emails only
  - `RemoveRuleTool` — calls `RulesRepository.delete()`
  - `SubmitToMediaFeedTool` — strips Markdown to plain text; HTTP POST to `MEDIA_FEED_URL`
- `MediaFeedClient` — plain HTTP POST, no auth
- `ActivityLogger` — writes `LogEntry` to `mailkick.log` DynamoDB; one call per tool execution; also writes `ERROR` and `OVERSIZE` entries

**Done when:** a standalone `main()` test processes a real email end-to-end (S3 prompt → Anthropic → tool execution → log entry written).

---

## Phase 9 — `mailkick-server` — Core Pipeline

**Goal:** Full email processing pipeline running as a Spring Boot application.

**Tasks:**
- `MailKickApplication` — reads env vars, initialises all components in dependency order, registers shutdown hook
- `TriageProcessor` — orchestrates the full pipeline:
  1. Rules check (`RulesChecker` → `RuleExecutor`)
  2. Email normalisation (`EmailNormaliser`)
  3. Oversize check
  4. LLM call (`AnthropicClient`)
  5. Tool execution (`ToolRegistry`)
  6. Finalise (destination + read/unread state)
  7. Error fallback (Inbox + unread + flagged on any failure)
  8. Triage-stuck health flip if safety move fails
- `HealthController` — `GET /health`; aggregates `HealthStatus` from S3 loader, JMAP client, DynamoDB, Anthropic; returns `200` or `500` with JSON error body; respects 5-minute persistence threshold

**Done when:** application starts, connects to FastMail, processes a real email in Triage end-to-end, logs the action, and `/health` returns `200`.

---

## Phase 10 — Build + Docker

**Goal:** Self-contained Docker image built and runnable.

**Tasks:**
- Complete `build.sh` — validates `build.properties`, runs `mvn clean package -DskipTests`, retrieves all secrets from Secrets Manager, generates `credentials.sh`, builds Docker image, saves to `mailkick-docker/dist/mailkick.tar`, cleanup prompt
- `build.properties` with all required keys documented
- `Dockerfile` — Amazon Corretto 21 base, `ADD` JAR + `credentials.sh` + `launch.sh`
- `scripts/launch.sh` — `source /credentials.sh && java -jar /app.jar`
- `run.sh` — loads and runs the image locally for testing

**Done when:** `./build.sh` produces `mailkick.tar`; `docker load` + `docker run` starts the application and processes a real Triage email.

---

## Deferred — Daily Digest

Depends on core system (Phases 1–10) being stable. Covers:
- `DigestRunner` — `@Scheduled` task aligned to `digestTime` in configured `timezone`; queries all `mailkick.log` entries regardless of date (accumulates across missed days); serialises entries into XML; calls Anthropic with `digestPromptName` prompt; creates digest email in Inbox via JMAP `Email/set`; deletes log entries via `BatchWriteItem` only on success
- Skip if no log entries exist

## Deferred — Rules Management Web UI

Depends on core system (Phases 1–11) being stable. Covers:
- `TokenValidator` — parses `X-API-Key: <base64-sig>:<iso-ts>` header; verifies Ed25519 signature using baked-in `MAILKICK_PUBLIC_KEY`; checks timestamp within 24 hours; returns `401` on failure
- `RulesController` — `GET /ui` with sender context (from, subject, to params); `POST /ui/rules` create; `PUT /ui/rules/{sender}` update; `DELETE /ui/rules/{sender}` delete; all validated via `TokenValidator`
- `mailkick-dashboard` templates — rules list, sender detail view, add/edit/delete forms
- Wired into `mailkick-server` as a dependency

## Deferred — Thunderbird Extension

Depends on Rules Management Web UI being complete and stable. Covers:
- Extension scaffold (manifest, sidebar panel)
- Settings UI (server URL, Base64 private key)
- Right-click context menu → "Show in MailKick"
- Ed25519 signing of ISO timestamp in JavaScript
- `fetch()` calls to MailKick `/ui` with `X-API-Key` header
- Rendering returned HTML fragments in the sidebar panel

---

## Dependency Map

```
Phase 1  (skeleton)
    └── Phase 2  (model)
            ├── Phase 3  (keygen)       ← standalone, no further deps
            ├── Phase 4  (jmap core)
            │       └── Phase 5  (push + poll)
            │               └── Phase 6  (rules)
            │                       └── Phase 7  (agent - prompt + LLM)
            │                               └── Phase 8  (agent - tools + log)
            │                                       └── Phase 9  (server - pipeline)
            │                                               └── Phase 10 (docker)  ← DONE
            │
            └── (deferred: Digest → Rules UI → Thunderbird Extension)
```
