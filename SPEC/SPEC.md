# MailKick — Specification

## Overview

MailKick is a Java-based, Docker application that acts as an AI email agent for a FastMail account. It monitors a designated **Triage** folder and processes incoming emails using a combination of deterministic rules and LLM-based reasoning (Anthropic). Agent instructions are stored as a file in S3, managed via CDK, and reloaded at startup and after that every 5 minutes.

---

## Technology Stack

- **Java 21**
- **Spring Boot 3.x** (latest stable compatible with Java 21)
- **Maven** — multi-module build
- **Group ID:** `co.kuznetsov`, **Artifact:** `mailkick`
- **Code package root:** `co.kuznetsov.mailkick`
- **Checkstyle + SpotBugs** added from first project setup
- **SLF4J** for logging (stdout, structured for containers)
- **No Lombok**
- **Anthropic** access via a community Java SDK
- **HTML to Markdown:** [CopyDown](https://github.com/furstenheim/copy_down) (`io.github.furstenheim:copy_down`)
- **HTTP port:** application listens on `8080` inside the container; host port mapping decided at deployment time

---

## FastMail Integration

FastMail exposes a **JMAP** API (RFC 8620 / RFC 8621). MailKick uses the FastMail API token to authenticate all JMAP calls.

### Trigger: Triage Folder (and Sent Folder)

MailKick watches the FastMail `Triage` folder — and optionally the `Sent` folder — using a shared SSE connection that fans out signals to per-mailbox worker queues, each backed by its own independent fallback poller.

#### Signal intake

**Primary — EventSource push:**
On startup, MailKick discovers the JMAP session endpoint (`GET https://api.fastmail.com/jmap/session`) to obtain the `eventSourceUrl`, then opens a persistent SSE connection subscribing to `Email` state changes. When FastMail pushes a `StateChange` event, MailKick calls `Email/changes` to get the list of new email IDs and **fans them out to every registered signal queue** — the triage queue and, when the `SentWorker` is wired in, the sent queue as well. The connection stays open (`closeafter=no`) and a reconnect loop with exponential backoff handles dropped connections or read timeouts.

**Fallback — polling every 60 seconds:**
Each mailbox has its own independent `FolderPoller` with its own `AtomicReference<String>` for the last known JMAP state. Triage and Sent pollers do not share state and cannot interfere with each other. Each poller runs every 60 seconds using `Email/changes` from its own last known state string. If SSE is healthy this is a no-op; it acts as a safety net for missed events.

Both mechanisms produce **signals** — a signal is simply an email ID that may need processing. Push and polling never process emails directly.

#### TriageMonitor — pipeline orchestrator

`TriageMonitor` owns the full signal pipeline. It:

1. Resolves all mailbox IDs and fetches initial JMAP states.
2. Scans both monitored folders on startup and seeds the queues with any emails already present.
3. Constructs the `EventSourceListener` with the full list of signal queues to fan out to.
4. Constructs one `FolderPoller` per mailbox (each with its own state reference).
5. Constructs one worker thread per mailbox (`TriageWorker` for Triage, `SentWorker` for Sent).
6. Manages thread lifecycle — starts all threads together, stops and interrupts them on shutdown.

```
[FastMail SSE]
      |
      |  StateChange → Email/changes
      v
[EventSourceListener]  ──── fan-out ────┬──→  [Triage signal queue]  →  [TriageWorker]
                                        └──→  [Sent signal queue]    →  [SentWorker]

[FolderPoller (Triage, own state)]  ────────→  [Triage signal queue]
[FolderPoller (Sent,   own state)]  ────────→  [Sent signal queue]
```

#### Processing Workers

**TriageWorker** — for each signal dequeued:
1. Fetch the email via JMAP and check its current `mailboxIds`.
2. If the email is no longer in Triage — it has already been processed (or manually moved); skip silently, no log entry.
3. If the email is still in Triage — run the full processing pipeline (rules check → LLM → tool execution → finalise).

**SentWorker** — for each signal dequeued:
1. Fetch the email via JMAP and check its current `mailboxIds`.
2. If the email is no longer in Sent — skip silently.
3. If the email is still in Sent — move it to Inbox, marked as read.

Both workers are idempotent by design: the email's current mailbox membership is the source of truth. Duplicate signals from SSE + poll, signals replayed after restart, or any other redundancy are handled trivially — the worker re-checks and skips.

**Concurrency:** all JMAP API calls are **strictly sequential** through a single JMAP client instance. No parallel calls are issued to FastMail. Each worker is single-threaded, ensuring no two emails are processed in parallel.

### Agent Prompt

The LLM system prompt and Anthropic model selection are stored together as a file in **S3**, managed independently via CDK (versioning enabled on the bucket). MailKick loads this config on startup and reloads it every **5 minutes**.

The S3 bucket and object key are configured via `build.properties` and baked into the image at build time. 

The config is an XML file with the following structure:

```/dev/null/agent-config.xml#L1-30
<?xml version="1.0" encoding="UTF-8"?>
<config>
    <model>claude-3-5-haiku-latest</model>
    <timezone>America/New_York</timezone>
    <maxEmailSizeTokens>100000</maxEmailSizeTokens>
    <defaultPromptName>general</defaultPromptName>
    <triageFolder>Inbox/Triage</triageFolder>
    <spamFolder>Spam</spamFolder>
    <markUnread>
        <folder>Inbox</folder>
    </markUnread>
    <prompts>
        <prompt name="general"><![CDATA[
...default categorisation instructions...
        ]]></prompt>
    </prompts>
</config>
```

- **`model`** — Anthropic model identifier; changing requires only an S3 update, no rebuild
- **`defaultPromptName`** — key into `prompts` used for all emails that do not match a `MOVE_TO_FOLDER_WITH_PROCESSING` rule
- **`triageFolder`** — full path of the Triage folder to monitor (default: `Inbox/Triage`)
- **`spamFolder`** — full path of the Spam destination folder (default: role-based junk mailbox)
- **`markUnread`** — list of folder path patterns whose emails should be marked **unread** on arrival; everything else is marked **read**; supports exact paths (`Inbox`) and wildcard prefixes (`Inbox/Feed/*`)
- **`prompts`** — map of named prompts; each contains categorisation criteria and prescribed actions for that category of email

---

## Email Processing Pipeline

When an email arrives in (or is found in) the **Triage** folder, it is processed through the following sequential pipeline:

```
[Email in Triage]
       |
       v
[1. Rules Check]
       |
       |── SPAM / TRASH / ERASE ────────────────────────────→  Apply, done
       |── MOVE_TO_FOLDER_NO_PROCESSING ────────────────────→  Move, done
       |── MOVE_TO_FOLDER_WITH_PROCESSING ──→  Move to targetFolder
       |                                              |
       |                                              v
       |                                    [2. LLM Reasoning]
       |                                              |
       no rule match                                  v
       |                                    [3. Tool Execution]
       v                                              |
[2. LLM Reasoning]  ←─────────────────────────────────
       |
       v
[3. Tool Execution]  (one or more tools selected by LLM)
       |
       v
[4. Finalise]
       |
       |── email moved to non-Inbox folder ──→  Mark as read
       |── email moved to Inbox ─────────────→  Mark as unread
       |── no move tool called ──────────────→  Move to Inbox, mark as unread
```

### Step 1 — Rules Check

Before any LLM call, the sender of the email is looked up in the **Rules** table in DynamoDB. Rules always bypass the LLM entirely — if a rule matches, it is applied immediately and processing stops.

**Sender extraction:**
The sender is always the **bare email address** extracted from the `From` header, ignoring any display name. For example, `"Foo Bar" <foo@example.com>` is treated as `foo@example.com`. Display names play no role in rule matching.

**Lookup order:**
1. Exact sender email address (e.g., `newsletter@example.com`)
2. Sender domain (e.g., `example.com`)
3. No match → proceed to LLM pipeline

**Rule types:**

| Type | Parameters | Behaviour |
|---|---|---|
| `MOVE_TO_FOLDER_NO_PROCESSING` | `targetFolder` | Move to named folder, mark as read, no LLM involved |
| `MOVE_TO_FOLDER_WITH_PROCESSING` | `targetFolder`, `promptName` | Move to destination folder immediately (clears Triage), then run the named prompt; LLM tool execution may move it further |
| `SPAM` | — | Move to FastMail Spam folder |
| `TRASH` | — | Move to FastMail Trash folder (recoverable) |
| `ERASE` | — | Permanent delete via JMAP `Email/set` `destroy` (not recoverable) |

**DynamoDB table schema:**

| Attribute | Type | Notes |
|---|---|---|
| `sender` | String (PK) | Email address or domain (e.g., `user@example.com` or `example.com`) |
| `ruleType` | String | One of the rule types above |
| `targetFolder` | String | Required for `MOVE_TO_FOLDER_NO_PROCESSING` and `MOVE_TO_FOLDER_WITH_PROCESSING` |
| `promptName` | String | Required for `MOVE_TO_FOLDER_WITH_PROCESSING`; must match a key in the S3 config `prompts` map |

DynamoDB table name: **`mailkick.rules`**
AWS region: **`us-east-2`**

See [`DDB.md`](./DDB.md) for the full DynamoDB schema, capacity, IAM permissions, and example items for all MailKick tables.

### Step 2 — LLM Reasoning (Anthropic)

The normalised XML payload is sent to the Anthropic model specified in the S3 config, together with the resolved prompt. The prompt to use is determined as follows:
- If the email matched a `MOVE_TO_FOLDER_WITH_PROCESSING` rule — use the `promptName` specified in the rule
- Otherwise — use the prompt identified by `defaultPromptName` in the S3 config

The prompt contains categorisation criteria and the action to take for each category. The model selects one or more **tools** to execute and provides their parameters.

Tools are declared to the Anthropic SDK with a name, description, and input schema. The model returns structured tool call instructions; MailKick executes them locally. The internal organisation of tool implementations is an implementation detail.

**Single-turn tool calling:**
MailKick uses a **single-turn** interaction model. The LLM receives the email and full tool set in one call, returns all tool calls it wants executed in that single response, and MailKick executes them in order. The conversation does not loop back to the LLM after tool execution.

This is appropriate because all MailKick tools are terminal actions (move, archive, spam, submit, add/remove rule) whose results the LLM does not need to reason about before deciding subsequent actions. Single-turn keeps cost and latency low (1 Anthropic API call per email) and the implementation simple.

The LLM is the final authority for unmatched emails — every email that reaches this step will be categorised and acted upon according to the S3 prompt. There is no fallback beyond the LLM.

### Step 3 — Tool Execution

Tools available to the LLM depend on the calling context (triage, archive, digest) and per-prompt configuration.

#### Triage — default tools

| Tool | Description |
|---|---|
| `move_to_folder` | Move email to a named FastMail folder (read/unread applied per `markUnread` config) |
| `archive` | Move email to the FastMail Archive folder |
| `spam` | Move email to the configured Spam folder |
| `trash` | Move email to the Trash folder |
| `mark_as_read` | Mark the email as read without moving it |
| `mark_as_unread` | Mark the email as unread without moving it |

#### Archive — default tools

| Tool | Description |
|---|---|
| `move_chain` | Move all emails in the settled thread to a specified destination folder and strip arrival tags |

#### Digest

No tools. The digest LLM call is text-only (`generateText`) — the model returns a plain-text summary with no tool use.

#### Extra tools (opt-in per prompt)

| Tool | Description |
|---|---|
| `submit_to_media_feed` | Strip Markdown from the normalised body to plain text, then POST to the media feed service with a compression factor specified by the agent prompt. Only available when `MEDIA_FEED_URL` is set at build time. |

Extra tools are not included in any prompt by default. To enable one for a specific prompt, add `extraTools="<comma-separated names>"` to its `<prompt>` element in the S3 config XML:

```xml
<prompt name="triagePrompt" extraTools="submit_to_media_feed">
  ...
</prompt>
```

#### Disallowing tools per prompt

Standard tools can be explicitly excluded from a prompt using `disallowTools`:

```xml
<prompt name="archivePrompt" disallowTools="spam,trash">
  ...
</prompt>
```

Tool names in `extraTools` and `disallowTools` are validated against the registered tool registry on config load. An unknown name causes a validation failure and the config reload is rejected.

### Step 4 — Finalise

After tool execution, MailKick applies the following finalisation rules:

**Destination:**
- If a move tool (`move_to_folder`, `archive`, `spam`, `trash`) was called — the email is already in its final location
- If no move tool was called — the email is moved to **Inbox** as the default

**Read/unread state — folder policy:**

Before every move, MailKick applies the `markUnread` configuration to determine the read/unread state of the destination folder:
- If the destination matches a `markUnread` pattern — email is marked **unread** before the move
- If not matched — email is marked **read** before the move
- Default (no `markUnread` config) — `Inbox` is unread, everything else is read

The `mark_as_read` and `mark_as_unread` tools allow the LLM to explicitly override this policy after a move.

### Oversized Email Handling

After email normalisation (HTML to Markdown conversion, metadata extraction, XML assembly), MailKick estimates the token count of the resulting XML document. If it exceeds `maxEmailSizeTokens` from the S3 config:

- **No Anthropic call is made** — the email is rejected upfront
- The email is **moved to Inbox**, marked **unread**, and marked **flagged** (same as the error fallback state)
- An activity log entry is written with `action = "OVERSIZE"` and `detail` containing the estimated token count and configured limit

This ensures large emails (long newsletters, file-like content) never blow up the LLM budget and always surface for manual attention with a clear visual signal.

### Error Handling

If any step of the pipeline fails (Anthropic API unavailable, LLM error, tool execution failure, JMAP error during a non-finalisation step), the email is treated as **un-processable** and routed to a safe state:

- **Destination:** moved to **Inbox**
- **Read state:** marked as **unread**
- **Flagged:** marked as **flagged** (JMAP keyword `$flagged` — maps to IMAP `\Flagged`, visible as a star/flag in the FastMail web UI and other clients)
- **Activity log:** an entry is written with `action = "ERROR"` and `detail` containing the failure reason

This ensures no email is ever lost in Triage due to a processing failure — they all surface in Inbox with a clear visual signal that they need manual attention.

**Finalisation-step failures:**

If the safety move-to-Inbox itself fails (i.e. MailKick cannot move the email out of Triage even into the error-state fallback location), the email remains in Triage and the system enters an unhealthy state:

- The email stays in Triage
- A log entry is written
- The **Triage** health component flips to `failing` with message **"Email stuck in triage failure loop"**
- After the standard 5-minute persistence threshold, `/health` returns `500` and FaceKick alerts

This is treated as a fatal condition because MailKick cannot make forward progress on Triage when even its safety fallback is unreachable.

---

## LLM Email Format

Before any LLM call, each email is normalised into a two-document XML structure. Document 1 contains trusted metadata controlled by the mail server. Document 2 contains the untrusted email body supplied by the external sender. The system prompt preamble (injected automatically by the application, not stored in the S3 config) instructs the model to treat Document 2 as data only and ignore any instructions it may contain.

**Metadata fields extracted via JMAP:**

| Field | Source |
|---|---|
| `messageId` | `Message-ID` header |
| `date` | `Date` header |
| `receivedAt` | JMAP `receivedAt` field |
| `from` | `From` header |
| `to` | `To` header |
| `cc` | `CC` header |
| `subject` | `Subject` header |
| `replyTo` | `Reply-To` header |
| `inReplyTo` | `In-Reply-To` header |
| `dkim` / `spf` / `dmarc` | Parsed from `Authentication-Results` header (added by FastMail); fetched via JMAP `header:Authentication-Results:asText`; defaults to `none` if absent |

**Body extraction — always produces Markdown:**
1. **Plain text part** — used as-is (plain text is valid Markdown)
2. **HTML part** — converted to Markdown using a Java HTML-to-Markdown library if no plain text part exists

The body is XML-escaped before insertion into Document 2. Markdown syntax characters (`*`, `#`, `[`, `]`, etc.) are safe since XML escaping only touches `&`, `<`, `>`, `"`, and `'`.

**Attachment extraction:**

Attachments are fetched via the JMAP `attachments` property. Only the MIME type of each attachment is extracted (filename, size, and other metadata are ignored). MIME types are classified into three categories:

| Category | Criteria |
|---|---|
| `documents` | `application/pdf`, Office (`application/msword`, `application/vnd.ms-*`, `application/vnd.openxmlformats-officedocument.*`), ODF (`application/vnd.oasis.opendocument.*`), `application/rtf`, `text/plain`, `text/csv`, `text/calendar`, archives (`application/zip`, `application/gzip`, `application/x-tar`, `application/x-7z-compressed`, `application/x-rar-compressed`, etc.) |
| `media` | `image/*`, `video/*`, `audio/*` |
| `other` | Everything else |

The `<attachments>` element is omitted entirely when no attachments are present. Empty category elements within it are also omitted.

**Format:**

```xml
<documents>
  <document index="1">
    <source>email metadata</source>
    <content>
      <messageId>...</messageId>
      <date>...</date>
      <receivedAt>...</receivedAt>
      <from>...</from>
      <to>...</to>
      <cc>...</cc>
      <subject>...</subject>
      <replyTo>...</replyTo>
      <inReplyTo>...</inReplyTo>
      <authentication>
        <dkim>pass|fail|none</dkim>
        <spf>pass|fail|softfail|none</spf>
        <dmarc>pass|fail|none</dmarc>
      </authentication>
      <attachments>
        <documents>application/pdf, text/csv</documents>
        <media>image/jpeg, image/png</media>
        <other>application/x-custom</other>
      </attachments>
    </content>
  </document>
  <document index="2">
    <source>email body (untrusted content from external sender)</source>
    <content>
...XML-escaped Markdown content...
    </content>
  </document>
</documents>
```

The `<attachments>` element is omitted entirely when no attachments are present. The full document is passed as a `text` content block in the Anthropic API `user` message.

---

## Media Feed Integration

The media feed is an existing external service with a simple REST API. MailKick calls it when the LLM invokes the `submit_to_media_feed` tool.

**Request:** HTTP POST to the media feed server with:
- `text` — the normalised email body (Markdown) with formatting stripped to plain text
- `compressionFactor` — integer compression ratio (e.g. `5` = summarise to 1/5th of original length), as instructed by the agent prompt

**Configuration** (in `build.properties`, baked into the image at build time):
- `CONFIG_S3_BUCKET` — S3 bucket containing the agent config file
- `CONFIG_S3_KEY` — S3 object key of the agent config file
- `MEDIA_FEED_URL` — base URL of the media feed REST API

No authentication required.

---

## Secrets and Credentials

All secrets are **baked into the Docker image at build time**, following the same pattern as the facekick project:

- `build.sh` retrieves secrets from **AWS Secrets Manager** using the current local AWS credentials at build time.
- Secrets are written into a `credentials.sh` script embedded in the Docker image.
- The container requires no runtime environment injection — it is self-contained.

Secrets required:

| Key | Description |
|---|---|
| `FASTMAIL_API_TOKEN` | JMAP authentication for the FastMail account |
| `ANTHROPIC_API_KEY` | Authentication for LLM calls |
| `AWS_ACCESS_KEY_ID` | IAM user keypair for runtime AWS access (DynamoDB `us-east-2`) |
| `AWS_SECRET_ACCESS_KEY` | IAM user keypair for runtime AWS access (DynamoDB `us-east-2`) |

All four keys are stored in a **single AWS Secrets Manager secret** as a JSON object. Retrieved at build time and written into `credentials.sh`, which is embedded in the Docker image and sourced at application bootstrap.

---

## Build & Packaging

- `build.sh` at project root orchestrates the full build:
  1. Reads `build.properties` for configuration (secret names, AWS region, etc.)
  2. Runs `mvn clean package -DskipTests`
  3. Retrieves secrets from AWS Secrets Manager
  4. Generates `credentials.sh` and embeds it into the Docker build context
  5. Builds Docker image (`mailkick`)
  6. Saves image to `mailkick-docker/dist/mailkick.tar`
- Docker base image: **Amazon Corretto 21**
- Offers cleanup prompt after build (removes `credentials.sh` and intermediate files)

---

## Logging

- All logging via **SLF4J** to stdout
- **Logback configuration:** Spring Boot defaults (no custom `logback.xml`). The default pattern is `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message`, matching the facekick project.
- Key log events:
  - Application startup / shutdown
  - Agent prompt loaded from S3 (on startup and each reload)
  - Email received in Triage (sender, subject)
  - Rule match found (type, matched key)
  - LLM tool selection result
  - Each tool execution (success / failure)
  - DynamoDB read/write operations
  - S3 prompt reload (success / failure / no change)
  - Component health state change (healthy → failing or failing → recovered)

---

## Health API

MailKick exposes a health endpoint intended to be monitored by FaceKick.

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Returns system health status |

**Response:**
- `200 OK` — all components healthy
- `500 Internal Server Error` — one or more components are failing, with a JSON body describing which components are affected and the error details

**Components checked:**

| Component | Failure condition |
|---|---|
| S3 | Last prompt reload attempt failed |
| FastMail API | Last JMAP call failed or authentication rejected |
| DynamoDB | Last read/write attempt failed or authentication rejected |
| Anthropic API | Last LLM call failed or authentication rejected |
| Triage | Email stuck in Triage failure loop (safety move-to-Inbox failed) |

A component is only considered unhealthy if its failure has persisted for more than **5 minutes**. Transient errors (e.g. a single failed API call) do not immediately affect health status. Once a component has been failing continuously for 5 minutes, the endpoint returns `500` until it recovers.

The JSON error body lists all currently unhealthy components with their error details and the time the failure started.

**FaceKick configuration:** monitor `/health` with `expectHealthy: true`.

### Recovery Policy

MailKick does **not** perform any automated recovery actions when health flips to failing — no self-healing, escalation, or auto-restart logic.

However, the health status itself **clears automatically** as soon as the underlying problem resolves: each normal scheduled retry (polling, prompt reload, etc.) re-tests the affected component, and a single successful call flips the component back to `healthy`. The `/health` endpoint then returns `200` again with no manual intervention.

When FaceKick alerts on a `500` health response, the **owner is responsible for diagnosing and determining the recovery path** — e.g. inspecting logs, restarting the container, fixing upstream services, manually clearing stuck emails, or rotating credentials. Transient issues will resolve on their own; persistent ones require action.

---

## Activity Log

MailKick maintains a structured log of every action taken on inbound emails. The log is stored in DynamoDB and survives container restarts.

**DynamoDB table:** `mailkick.log`
**AWS region:** `us-east-2`

**Log entry schema:**

| Attribute | Type | Notes |
|---|---|---|
| `date` | String (PK) | Date of the action, e.g. `2025-01-15` |
| `timestamp` | String (SK) | ISO 8601 timestamp of the action |
| `messageId` | String | Email `Message-ID` |
| `from` | String | Sender address |
| `subject` | String | Email subject |
| `action` | String | Tool called (e.g. `move_to_folder`, `spam`) or rule type if matched by rules engine (e.g. `SPAM`, `ERASE`) |
| `detail` | String | Extra context — target folder name, `promptName` used, compression factor, etc. |

One entry is written per action. If the LLM calls multiple tools for a single email, multiple entries are written.

---

## Daily Digest

MailKick generates a daily digest email summarising all actions taken that day and places it directly into Inbox as unread.

### Configuration

The following fields are added to the S3 agent config XML:

```/dev/null/agent-config-digest.xml#L1-19
<?xml version="1.0" encoding="UTF-8"?>
<config>
    <model>claude-sonnet-4-5</model>
    <timezone>America/New_York</timezone>
    <maxEmailSizeTokens>100000</maxEmailSizeTokens>
    <defaultPromptName>general</defaultPromptName>
    <digestTime>07:00</digestTime>
    <digestPromptName>digest</digestPromptName>
    <digestSenderAddress>mailkick@example.com</digestSenderAddress>
    <prompts>
        <prompt name="general"><![CDATA[
...default categorisation instructions...
        ]]></prompt>
        <prompt name="digest"><![CDATA[
...instructions for summarising the activity log...
        ]]></prompt>
    </prompts>
</config>
```

- **`timezone`** — application-wide timezone (e.g. `UTC`, `America/New_York`); used for digest scheduling and all time-based operations
- **`maxEmailSizeTokens`** — upper limit on tokens in the normalised email (XML metadata + Markdown body) that MailKick will send to the LLM; oversized emails are rejected upfront (see **Oversized Email Handling**)
- **`digestTime`** — time of day to run the digest in 24h format (e.g. `07:00`), interpreted in `timezone`
- **`digestPromptName`** — key into `prompts` map used for the digest LLM call
- **`digestSenderAddress`** — `From` address on the digest email created in Inbox; must be an address in the same domain as the FastMail account (e.g. `mailkick@yourdomain.com`) so FastMail accepts the `Email/set` create

### Digest Flow

1. At the configured `digestTime`, MailKick reads all log entries for the current date from `mailkick.log`
2. Entries are serialised into XML (same approach as email normalisation) and sent to the Anthropic model with the `digestPromptName` prompt
3. The model produces a digest summary
4. MailKick creates the digest email directly in Inbox via JMAP `Email/set` (no sending — no Sent copy):
   - `From`: `digestSenderAddress`
   - `Subject`: `MailKick Daily Digest — <date>`
   - `Body`: digest summary from LLM
   - Marked as **unread**
5. All log entries for that date are deleted from `mailkick.log` after the digest email is successfully created

### Edge Cases

- If there are no log entries for the day, no digest email is created
- If the digest fails (LLM error, JMAP error), log entries are **not** deleted and the log does **not** rotate — entries accumulate across days until the digest succeeds. The next successful digest will cover all accumulated entries regardless of which day they were logged, producing a multi-day summary if needed

---

## Project Structure

Maven multi-module project, parent artifact `co.kuznetsov:mailkick`.

```
mailkick/
├── pom.xml                        (parent)
├── build.properties
├── build.sh
├── run.sh
├── mailkick-model/                (core data model)
├── mailkick-jmap/                 (FastMail JMAP client)
├── mailkick-rules/               (DynamoDB rules engine)
├── mailkick-agent/               (LLM agent, tools, email normalisation)
├── mailkick-server/              (Spring Boot app, health API)
├── mailkick-dashboard/           (Thymeleaf templates and static resources)
└── mailkick-docker/              (Dockerfile, build context)
    ├── download/                  (staging area for JAR + credentials.sh)
    └── dist/                      (output: mailkick.tar)
```

### `mailkick-model`
Core data model classes and persistence utilities shared across modules. Contains:

**Data model:**
- `Email` — normalised email representation (metadata + Markdown body)
- `Rule` — DynamoDB rule record with all rule types and parameters
- `RuleType` — enum: `MOVE_TO_FOLDER_NO_PROCESSING`, `MOVE_TO_FOLDER_WITH_PROCESSING`, `SPAM`, `TRASH`, `ERASE`
- `MailKickConfig` — deserialised S3 config (model, timezone, maxEmailSizeTokens, defaultPromptName, digestTime, digestPromptName, digestSenderAddress, prompts map)
- `LogEntry` — DynamoDB activity log record
- `HealthStatus` — per-component state (`HEALTHY` / `FAILING`) + failure message + failure start time

**DynamoDB utilities (`co.kuznetsov.mailkick.model.ddb`):**
- `RulesDdbRepository` — read/write/delete/scan `mailkick.rules`; `GetItem` by exact sender, `GetItem` by domain, `PutItem`, `DeleteItem`, `Scan`
- `LogDdbRepository` — read/write/delete `mailkick.log`; `PutItem`, `Query` by date, `BatchWriteItem` for bulk delete after digest
- `DdbMapper` — converts between model classes (`Rule`, `LogEntry`) and DynamoDB `AttributeValue` maps
- `DdbClientFactory` — creates the AWS SDK `DynamoDbClient` from env vars (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`)

**S3 config utilities (`co.kuznetsov.mailkick.model.config`):**
- `MailKickConfigLoader` — reads the S3 object (bucket + key from env vars), deserialises JSON into `MailKickConfig`
- `MailKickConfigValidator` — validates required fields are present and consistent (e.g. `defaultPromptName` exists in `prompts` map, `digestPromptName` exists if `digestTime` is set, `digestTime` is valid 24h format)
- `MailKickConfigJson` — Jackson-based JSON serialisation/deserialisation for `MailKickConfig`

**General utilities:**
- `JsonUtil` — shared Jackson `ObjectMapper` configuration

### `mailkick-jmap`
FastMail JMAP client. Contains:
- `JmapClient` — authenticated HTTP client for JMAP API calls
- `JmapSession` — session discovery and `eventSourceUrl` resolution
- `MailboxResolver` — resolves folder names to JMAP mailbox IDs
- `EmailFetcher` — `Email/changes` + `Email/get` calls; also folder queries and thread fetches
- `EmailNormaliser` — extracts metadata, converts HTML to Markdown, builds XML document
- `EmailMover` — `Email/set` for moving emails, setting keywords, and read/unread state
- `EventSourceListener` — persistent SSE connection; fans out new email IDs to all registered signal queues; exponential backoff reconnect
- `FolderPoller` — 60-second fallback poller; each instance owns its own JMAP state reference and `FolderPollerStrategy` for folder-specific reset logic
- `TriageMonitor` — orchestrates the full signal pipeline: wires SSE fan-out, one `FolderPoller` per mailbox, and one worker thread per mailbox; manages thread lifecycle
- `TriageWorker` — single-threaded consumer of the Triage signal queue; runs the full processing pipeline for each confirmed Triage email
- `SentWorker` — single-threaded consumer of the Sent signal queue; moves confirmed Sent emails to Inbox as read

### `mailkick-rules`
DynamoDB-backed rules engine. Contains:
- `RulesRepository` — DynamoDB read/write for `mailkick.rules` table
- `RulesChecker` — exact email → domain lookup, returns matching `Rule` or empty
- `RuleExecutor` — applies a matched rule via `JmapClient` (move, spam, trash, erase)

### `mailkick-agent`
LLM agent and tool execution. Contains:
- `AgentPromptLoader` — loads and reloads `MailKickConfig` from S3 every 5 minutes
- `AnthropicClient` — Anthropic SDK wrapper, sends XML email + prompt, handles tool call loop
- `ToolRegistry` — declares all tools to the Anthropic SDK with names, descriptions, input schemas
- Tool implementations:
  - `MoveToFolderTool`
  - `ArchiveTool`
  - `SpamTool`
  - `AddRuleTool`
  - `RemoveRuleTool`
  - `SubmitToMediaFeedTool`
- `MediaFeedClient` — HTTP POST to media feed service
- `ActivityLogger` — writes log entries to `mailkick.log` DynamoDB table
- `DigestRunner` — scheduled task; reads log entries, calls Anthropic with digest prompt, creates digest email via JMAP, deletes log entries on success

### `mailkick-server`
Spring Boot application entry point. Contains:
- `MailKickApplication` — main class, reads env vars from `credentials.sh`, initialises all components, registers shutdown hook
- `MailKickTriageProcessor` — implements `TriageProcessor`; orchestrates the full email processing pipeline (rules check → normalise → LLM → finalise)
- `HealthController` — `GET /health` endpoint, aggregates component health states

### `mailkick-docker`
Docker build context. Contains:
- `Dockerfile` — Amazon Corretto 21 base, adds JAR + `credentials.sh` + `launch.sh`
- `scripts/launch.sh` — sources `credentials.sh`, runs `java -jar /app.jar`
- `download/` — staging area populated by `build.sh` (gitignored)
- `dist/` — output directory for `mailkick.tar` (gitignored)

---

## AutoSpam

A recurring task (every 5 minutes) that automatically creates spam rules for senders whose emails have been sitting in a "purgatory" folder longer than a configured threshold, and keeps the Spam folder's read/unread state consistent with the system configuration.

### Configuration (in S3 XML config)

```/dev/null/agent-config.xml#L1-8
<autoSpam>
    <purgatoryFolder>Inbox/Untrusted</purgatoryFolder>
    <excludedDomains>kuznetsov.co,protonmail.com,proton.me,panarina.ca</excludedDomains>
    <purgatoryDays>7</purgatoryDays>
    <summaryFolder>Inbox</summaryFolder>
    <reportSender>mailkick@example.com</reportSender>
</autoSpam>
```

| Field | Description |
|---|---|
| `purgatoryFolder` | Folder to scan for old emails (full path, e.g. `Inbox/Untrusted`) |
| `excludedDomains` | Comma-separated list of domains never spam-listed |
| `purgatoryDays` | Emails older than this many days qualify |
| `summaryFolder` | Where the summary report email is placed |
| `reportSender` | Email address used as both From and To on the report |

### Flow

Runs immediately on startup, then every **5 minutes**.

**Part 1 — Purgatory processing** (skipped if no qualifying emails found):
1. Queries `purgatoryFolder` for emails older than `purgatoryDays`
2. For each qualifying email (deduplicated per sender per run):
   - Extracts the FROM address
   - If the sender domain is **not** in `excludedDomains`, and no rule already exists in DynamoDB for that sender → creates a `SPAM` rule for the exact email address
   - Applies `markUnread` read/unread policy for the Spam folder before moving
   - Moves the email to the Spam folder
3. If any new rules were created: renders a Thymeleaf HTML summary report listing all newly blocked senders and places it in `summaryFolder` (read/unread state follows `markUnread` folder rules)

**Part 2 — Spam folder consistency check** (always runs, even if no purgatory emails):
- Queries the Spam folder for emails whose read/unread state is inconsistent with the `markUnread` configuration
- For each inconsistent email: corrects the read state via JMAP `setRead`
- Example: if `spam` is not in `markUnread` (should be read) but the mail provider delivered an email as unread — it is marked read

**Deduplication guarantees:**
- Within a run: a sender is only processed once even if multiple emails from them are in the purgatory folder
- Across runs: a sender is skipped if a DynamoDB rule already exists for them (created by a previous run)

### Implementation

- `AutoSpamConfig` — model class in `mailkick-model`
- `AutoSpamRunner` — scheduled runner in `mailkick-server`; background thread runs every 5 minutes
- `autospam-report.html` — Thymeleaf template in `mailkick-dashboard`
- `EmailFetcher.queryEmailsOlderThan()` — JMAP `Email/query` with `before` filter
- `EmailFetcher.queryEmailsByReadState()` — JMAP `Email/query` with `hasKeyword`/`notKeyword: $seen` filter
- `EmailFetcher.createEmailInFolder()` — JMAP `Email/set` create (places email directly in mailbox without SMTP)
- `MailKickConfig.shouldMarkUnread()` — pattern-matching helper used for both purgatory moves and consistency check

---

## AutoArchive

A recurring task that classifies settled mail chains that have been moved to a designated archive staging folder. Each email is tagged with a JMAP keyword encoding the time MailKick first saw it in the folder. Once every email in a thread carries a tag older than the settling duration, the chain root is passed to an LLM which decides the final destination and moves the entire chain via a dedicated `move_chain` tool.

### Configuration (in S3 XML config)

```xml
<autoArchive>
    <archiveFolder>Archive/Inbox</archiveFolder>
    <settlingMinutes>10</settlingMinutes>
    <archivePromptName>archive</archivePromptName>
</autoArchive>
```

| Field | Default | Description |
|---|---|---|
| `archiveFolder` | — | Folder that receives emails to be classified (full path, e.g. `Archive/Inbox`). Required. |
| `settlingMinutes` | `10` | Minutes that must elapse after first-seen tagging before a thread is eligible for processing. |
| `archivePromptName` | — | Key into the S3 config `prompts` map used for the LLM classification call. Required. |

### Arrival tagging

The runner wakes every **2 minutes**. On each cycle the first step is always tagging:

1. Query `archiveFolder` for **all** email IDs currently in it (JMAP `Email/query` with `inMailbox` filter; also request `keywords` and `threadId` in the properties).
2. For each email that does **not** already carry a `mailkick-archived-<epoch-seconds>` keyword: apply the keyword `mailkick-archived-<now-epoch-seconds>` via JMAP `Email/set` patch (`keywords/mailkick-archived-<ts>: true`).
3. Emails that already have the keyword are left unchanged — their tag timestamp is preserved across cycles.

The keyword name encodes the tag time, so no external store is needed. JMAP user-defined keyword names must match `[a-zA-Z0-9_-]+` (RFC 8621 §2); `mailkick-archived-1748390400` is valid.

### Settling check and thread grouping

After tagging, identify threads ready for processing:

1. From the full set of emails in `archiveFolder` (already fetched above), read each email's `mailkick-archived-<ts>` keyword and parse the epoch timestamp.
2. An email is **settled** when `now - tag_epoch >= settlingMinutes`.
3. Group all emails by `threadId`. A thread is **ready** when **every** email in `archiveFolder` belonging to that thread is settled.
4. Threads that are not yet fully settled are left alone — they will be re-evaluated on the next cycle.

### Thread root resolution

For each ready thread:

1. Call `Thread/get` with the `threadId` to obtain all email IDs in the full thread (including emails that may be in other folders).
2. From the full thread list, retain only the IDs that are currently in `archiveFolder`.
3. Among that subset, pick the email with the **lowest `mailkick-archived-<ts>` tag epoch** — the email MailKick first placed in the folder — this is the **chain root**. `receivedAt` is not used: mail chains can span months, so the original send time has no bearing on which email arrived in the archive folder first.
4. Fetch and normalise the chain root using the same XML normalisation as triage (`EmailNormaliser`).

### LLM classification call

The normalised root email XML is sent to the Anthropic model with the `archivePromptName` prompt. The LLM is given only the `move_chain` tool. It must respond with exactly one `move_chain` call.

`move_chain` tool definition:
```
move_chain(destinationFolder: String)
```
- `destinationFolder` — full folder path to move the entire chain to (e.g. `Archive/Finance`).

On execution, `move_chain`:
1. Moves all emails from the thread that are currently in `archiveFolder` to `destinationFolder` (batched JMAP `Email/set`).
2. Removes the `mailkick-archived-<ts>` keyword from each moved email (clean-up; emails outside MailKick's `archiveFolder` should not carry the tag).

If the LLM does not call `move_chain`, the chain is left in place and retried on the next cycle after its tags age further. A warning is logged.

### Deduplication and idempotency

- **Within a cycle:** each `threadId` is processed at most once.
- **Across cycles:** after a successful `move_chain`, emails leave `archiveFolder` and lose their tags. They no longer appear in the initial `archiveFolder` query — the thread is naturally retired.
- **New arrivals to an existing thread:** if a new email arrives in `archiveFolder` after the rest of the thread has already settled, it gets tagged on first sight. The thread is blocked from processing until the new member's tag age also exceeds `settlingMinutes`. The rest of the thread's tags are older but the new member is the blocker — correct behaviour.
- **Partial move failure:** if a JMAP error leaves some emails un-moved, they retain their tags and will re-qualify for processing on the next cycle (their tags are already old enough). The chain root is re-resolved from whatever remains in `archiveFolder`.

### New JMAP primitives required

| Method | Description |
|---|---|
| `EmailFetcher.queryAllEmailsInMailbox(mailboxId)` | JMAP `Email/query` + `Email/get` fetching `id`, `threadId`, `keywords` for all emails in the mailbox. |
| `EmailFetcher.fetchThreadEmailIds(threadId)` | JMAP `Thread/get` — returns all email IDs in the thread. |
| `EmailMover.setKeyword(emailId, keyword, value)` | JMAP `Email/set` patch — sets or removes an arbitrary keyword on a single email. |
| `EmailMover.moveAllToMailboxAndRemoveKeyword(emailIds, mailboxId, keyword)` | Batched JMAP `Email/set` — moves a list of emails and removes the named keyword in one request. |

### Implementation

- `AutoArchiveConfig` — model class in `mailkick-model`
- `AutoArchiveRunner` — scheduled runner in `mailkick-server`; background thread wakes every 2 minutes
- `MoveChainTool` — tool executor in `mailkick-agent`; receives the thread email IDs in context, moves all of them and strips the arrival keyword on `move_chain` invocation
- `MailKickConfig.isAutoArchiveEnabled()` — convenience predicate
- Config XML parsing in `MailKickConfigXml`
- Bean wiring in `MailKickConfiguration`

---

## Open Questions

None — all questions resolved.

