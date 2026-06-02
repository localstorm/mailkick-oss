# MailKick

MailKick is a self-hosted AI email agent for FastMail. It monitors a designated **Triage** folder via the JMAP API and automatically routes each incoming email using a combination of deterministic sender rules and an Anthropic LLM. Actions include moving to folders, archiving, marking as spam, permanent deletion, and submitting content to an external media feed. A daily digest of all actions is composed by the LLM and delivered to your Inbox each morning.

It runs as a single Docker container with no external database beyond DynamoDB and S3.

For the full specification see [`SPEC/SPEC.md`](SPEC/SPEC.md). For the DynamoDB table definitions see [`SPEC/DDB.md`](SPEC/DDB.md).

## Tools

The set of tools available to the LLM depends on the calling context.

**Triage** (default tools, available to every triage prompt):

| Tool | Effect |
|---|---|
| `move_to_folder` | Move to a named folder |
| `archive` | Move to Archive |
| `spam` | Move to the configured Spam folder |
| `trash` | Move to Trash |
| `mark_as_read` | Mark as read without moving |
| `mark_as_unread` | Mark as unread without moving |

**Archive** (default tools, available to the archive prompt):

| Tool | Effect |
|---|---|
| `move_chain` | Move all emails in a settled thread to a destination folder |

**Digest**: no tools — the digest LLM call produces plain text only.

**Extra tools** (opt-in per prompt via `extraTools` attribute in the S3 config XML):

| Tool | Effect | Prerequisite |
|---|---|---|
| `submit_to_media_feed` | POST normalised body as plain text to the media feed service | `MEDIA_FEED_URL` set at build time |

To enable an extra tool for a prompt, add `extraTools="<name>"` to its `<prompt>` element. To block a standard tool from a prompt, add `disallowTools="<name>"`. Unknown tool names in either attribute cause a config validation failure. See [`SPEC/SPEC.md`](SPEC/SPEC.md) for details.

---

## Infrastructure setup

Before running MailKick you need:

1. A FastMail account with an API token
2. An Anthropic API key
3. An AWS account with:
   - A DynamoDB table **`mailkick.rules`** and **`mailkick.log`** in `us-east-2` (see [`SPEC/DDB.md`](SPEC/DDB.md) for exact schemas and IAM requirements)
   - An S3 bucket and object for the agent config XML (see [`mailkick-config.xml.sample`](mailkick-config.xml.sample) for a starting template and [`SPEC/SPEC.md`](SPEC/SPEC.md) — *Agent Prompt* section for the full config format)
   - An AWS Secrets Manager secret holding all five credentials (see *Secrets* below)

### Generating a CDK package

The DynamoDB tables, S3 bucket, IAM user, and Secrets Manager secret can all be managed by a CDK stack. You can ask an AI assistant to generate a TypeScript CDK package for you — point it at [`SPEC/DDB.md`](SPEC/DDB.md) and [`SPEC/SPEC.md`](SPEC/SPEC.md) and ask it to:

- Create the two DynamoDB tables with the key schemas and `PAY_PER_REQUEST` billing described in `DDB.md`
- Create a versioned S3 bucket for the agent config file
- Create an IAM user with the exact DynamoDB permissions listed in `DDB.md` and read access to the S3 config bucket
- Store the IAM keypair and the other secrets (`FASTMAIL_API_TOKEN`, `ANTHROPIC_API_KEY`) in a Secrets Manager secret as a JSON object
- Optionally output the S3 bucket name and secret ARN as CloudFormation outputs for use in `build.properties`

---

## Building

### Prerequisites

- Java 21
- Maven 3.x
- Docker
- AWS CLI configured with credentials that can read from Secrets Manager

### 1. Configure `build.properties`

Copy the template and fill in your values:

```sh
cp build.properties.template build.properties
```

| Property | Description |
|---|---|
| `AWS_REGION` | AWS region for Secrets Manager lookup at build time and DynamoDB at runtime (default: `us-east-2`) |
| `MAILKICK_SECRET_NAME` | Name (or ARN) of the Secrets Manager secret containing all five MailKick credentials |
| `CONFIG_S3_BUCKET` | S3 bucket name where the agent config XML lives |
| `CONFIG_S3_KEY` | S3 object key of the agent config XML (e.g. `config/agent-config.xml`) |
| `MEDIA_FEED_URL` | *(optional)* Base URL of the media feed REST API; required if `submit_to_media_feed` is enabled for any prompt via `extraTools` |

### 2. Run the build

```sh
./build.sh
```

This will:
1. Run `mvn clean package -DskipTests`
2. Fetch secrets from Secrets Manager using your local AWS credentials
3. Generate a `credentials.sh` file and embed it into the Docker image
4. Build the image tagged `mailkick:latest`
5. Save the image to `mailkick-docker/dist/mailkick.tar`
6. Prompt you to delete the intermediate `credentials.sh`

The resulting `mailkick.tar` is a self-contained image — no environment variables are needed at runtime.

---

## Deploying

### Transfer and load the image

Copy `mailkick-docker/dist/mailkick.tar` to your server, then:

```sh
docker load -i mailkick.tar
```

### Run the container

```sh
docker run -d \
  --name mailkick \
  --restart unless-stopped \
  -p 8080:8080 \
  mailkick:latest
```

The application listens on port `8080`. Map it to any host port you prefer.

### Health check

```sh
curl http://localhost:8080/health
```

Returns `200 OK` when all components (FastMail, Anthropic, DynamoDB, S3, Triage) are healthy. Returns `500` with a JSON body describing failing components if any have been unhealthy.

---

## Secrets

All credentials are stored as a single JSON object in AWS Secrets Manager. The secret is fetched once at build time and baked into the Docker image as a `credentials.sh` file — no environment variables are needed at container runtime.

### Secret JSON format

```json
{
  "FASTMAIL_API_TOKEN": "fmu1-...",
  "ANTHROPIC_API_KEY": "sk-ant-...",
  "AWS_ACCESS_KEY_ID": "AKIA...",
  "AWS_SECRET_ACCESS_KEY": "..."
}
```

### Field reference

| Key | Required | Description |
|---|---|---|
| `FASTMAIL_API_TOKEN` | Yes | FastMail API token used by the JMAP client to watch Triage and move emails |
| `ANTHROPIC_API_KEY` | Yes | Anthropic API key (`sk-ant-...`) for LLM calls |
| `AWS_ACCESS_KEY_ID` | Yes | IAM access key ID — must have DynamoDB read/write on `mailkick.rules` and `mailkick.log`, and S3 read on the config bucket (see [`SPEC/DDB.md`](SPEC/DDB.md) for exact IAM policy) |
| `AWS_SECRET_ACCESS_KEY` | Yes | IAM secret key corresponding to `AWS_ACCESS_KEY_ID` |
