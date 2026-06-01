# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```sh
# Full Docker build (fetches AWS secrets, builds image, saves to mailkick-docker/dist/mailkick.tar)
./build.sh

# Maven compile + package only (no Docker, no secrets)
mvn clean package -DskipTests

# Compile a single module
mvn clean package -DskipTests -pl mailkick-jmap

# Run static analysis (Checkstyle + SpotBugs run automatically during verify)
mvn verify -DskipTests

# Run the server locally (requires credentials.sh to exist with env vars)
./run.sh
```

`build.properties` must exist (copy from `build.properties.template`) before running `build.sh`. It supplies `AWS_REGION`, `MAILKICK_SECRET_NAME`, `CONFIG_S3_BUCKET`, `CONFIG_S3_KEY`, and `MEDIA_FEED_URL`.

## Architecture

MailKick is an AI email agent for FastMail. It watches the **Triage** folder via JMAP and processes each email through a rules → LLM → tool-execution pipeline. Built as a Maven multi-module project targeting Java 21 / Spring Boot 3.3.

### Module dependency chain

```
mailkick-model  ←  mailkick-jmap  ←  mailkick-rules  ←  mailkick-agent  ←  mailkick-server
                                                                         ←  mailkick-dashboard (resources only)
mailkick-keygen  (standalone, no Spring)
```

### Module responsibilities

| Module | Purpose |
|---|---|
| `mailkick-model` | Shared data model (`Email`, `Rule`, `LogEntry`, `MailKickConfig`), DynamoDB repositories (`RulesDdbRepository`, `LogDdbRepository`), S3 config loader |
| `mailkick-jmap` | FastMail JMAP client — SSE push listener, 60s fallback poller, email fetch/normalise/move, triage worker |
| `mailkick-rules` | Rules engine — DynamoDB lookup (exact sender → domain fallback), rule execution via JMAP |
| `mailkick-agent` | Anthropic SDK wrapper, tool registry, all tool implementations, media feed client, activity logger, digest runner |
| `mailkick-server` | Spring Boot entry point, pipeline orchestrator, `/health` endpoint, Thymeleaf rules UI, Ed25519 token validation |
| `mailkick-dashboard` | Thymeleaf templates + static assets (no Java code) |
| `mailkick-keygen` | Standalone CLI that generates Ed25519 keypairs and prints them to stdout |
| `mailkick-docker` | Dockerfile (Amazon Corretto 21), launch script, build staging areas |

### Email processing pipeline

1. **Signal intake** — SSE push (`EventSourceListener`) and 60s poll (`TriagePoller`) both enqueue email IDs onto a shared in-memory queue. They never process emails directly.
2. **Worker** — single-threaded `TriageWorker` dequeues IDs and re-checks each email's current mailbox; emails no longer in Triage are silently skipped (idempotency).
3. **Rules check** — `RulesChecker` does exact-sender then domain lookup in DynamoDB `mailkick.rules`. A match bypasses the LLM entirely.
4. **LLM reasoning** — `AnthropicAgent` sends a normalised XML document (metadata + CDATA Markdown body) to the Anthropic model. Single-turn: the model returns all tool calls in one response.
5. **Tool execution** — `ToolExecutor` runs the tools in order: `move_to_folder`, `archive`, `spam`, `trash`, `mark_as_read`, `mark_as_unread`, `add_rule`, `remove_rule`, `submit_to_media_feed`.
6. **Finalise** — if no move tool was called, email goes to Inbox unread. Read/unread state is driven by `markUnread` patterns in the S3 config before every move.

**Error fallback:** any pipeline failure moves the email to Inbox, marks it unread and flagged (`$flagged`), and writes an `ERROR` log entry. If even the fallback move fails, the `Triage` health component flips to failing.

### Configuration

Runtime configuration is an XML file stored in S3 (bucket + key from env vars baked into the image). Reloaded every 5 minutes. Key fields: `model`, `timezone`, `maxEmailSizeTokens`, `defaultPromptName`, `triageFolder`, `spamFolder`, `markUnread`, `prompts` (named map), `digestTime`, `digestSenderAddress`, `autoSpam`.

### AWS infrastructure

- **DynamoDB (us-east-2):** `mailkick.rules` (PK: `sender`) and `mailkick.log` (PK: `date`, SK: `timestamp`)
- **S3:** agent config XML (bucket/key from env)
- **Secrets Manager:** single JSON secret with `FASTMAIL_API_TOKEN`, `ANTHROPIC_API_KEY`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `MAILKICK_PUBLIC_KEY`

### Authentication

Web UI requests carry `X-API-Key: <base64-ed25519-sig>:<iso-timestamp>`. MailKick verifies the Ed25519 signature against the baked-in public key and rejects tokens older than 24 hours.

## Code Style

Enforced by Checkstyle (runs at `validate` phase) and SpotBugs (runs at `verify` phase):

- No Lombok — plain Java
- No `System.out.println` — use SLF4J logger
- No star imports or unused imports
- Max line length: 140 characters; no tabs; no trailing whitespace
- Naming: `UpperCamelCase` for types, `lowerCamelCase` for methods/fields/variables, `UPPER_SNAKE_CASE` for constants
- Opening brace on same line (end-of-line style)
- One statement per line
- JavaDoc required on public classes only (not on methods)
- Magic numbers need named constants (exceptions: `-1`, `0`, `1`, `2`)
- `@Override` required where applicable
