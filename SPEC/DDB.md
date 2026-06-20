# MailKick — DynamoDB Schema

MailKick uses two DynamoDB tables, both provisioned in **`us-east-2`**.

Tables must exist before the application starts. MailKick does not create or modify table definitions at runtime — it only reads and writes data.

---

## Table 1: `mailkick.rules`

Holds sender-based routing rules consulted before any LLM call. See the **Rules Check** section of `SPEC.md` for the lookup semantics.

### Keys

| Attribute | Type | Role |
|---|---|---|
| `sender` | String | Partition key (PK) |

The `sender` value is either an email address (`user@example.com`) or a bare domain (`example.com`). The application performs two lookups in order: exact email match first, then domain match.

### Attributes

| Attribute | Type | Required | Notes |
|---|---|---|---|
| `sender` | String | yes | Partition key (see above) |
| `ruleType` | String | yes | One of: `MOVE_TO_FOLDER_NO_PROCESSING`, `MOVE_TO_FOLDER_WITH_PROCESSING`, `SPAM`, `TRASH`, `ERASE` |
| `targetFolder` | String | conditional | Required when `ruleType = MOVE_TO_FOLDER_NO_PROCESSING` or `MOVE_TO_FOLDER_WITH_PROCESSING`; absent otherwise |
| `promptName` | String | conditional | Required when `ruleType = MOVE_TO_FOLDER_WITH_PROCESSING`; must match a key in the S3 config `prompts` map |

### Capacity

- **Billing mode:** `PAY_PER_REQUEST` (on-demand). Volume is low — at most a few writes per day from the LLM `add_rule` tool, and one read per inbound email.

### Example items

```/dev/null/rule-example-1.json#L1-5
{
  "sender": "newsletter@example.com",
  "ruleType": "SPAM"
}
```

```/dev/null/rule-example-2.json#L1-6
{
  "sender": "example.com",
  "ruleType": "MOVE_TO_FOLDER_NO_PROCESSING",
  "targetFolder": "Archive/Newsletters"
}
```

```/dev/null/rule-example-3.json#L1-7
{
  "sender": "finance@bank.com",
  "ruleType": "MOVE_TO_FOLDER_WITH_PROCESSING",
  "targetFolder": "Finance",
  "promptName": "finance"
}
```

---

## Table 2: `mailkick.log`

Activity log of every action taken on inbound emails. Consumed by the daily digest and cleared after each successful digest run.

### Keys

| Attribute | Type | Role |
|---|---|---|
| `date` | String | Partition key (PK) |
| `timestamp` | String | Sort key (SK) |

The `date` partition key (e.g. `2025-01-15`) groups all entries for one calendar day, making the daily digest a single `Query` operation per day. The `timestamp` sort key (ISO 8601, e.g. `2025-01-15T10:30:00.123Z`) orders entries chronologically within the day.

> **Note:** `timestamp` and `from` are DynamoDB reserved words but are used as-is for key/attribute names since reserved words only need escaping in expressions (filter conditions, key conditions), not in `PutItem` attribute maps.

The date is computed in the application timezone configured in the S3 agent config (`timezone` field).

### Attributes

| Attribute | Type | Required | Notes |
|---|---|---|---|
| `date` | String | yes | Partition key (see above) |
| `timestamp` | String | yes | Sort key (see above) |
| `messageId` | String | yes | Email `Message-ID` header |
| `from` | String | yes | Sender address |
| `to` | String | no | TO recipients (omitted if empty) |
| `cc` | String | no | CC recipients (omitted if empty) |
| `subject` | String | yes | Email subject |
| `action` | String | yes | Tool called (e.g. `move_to_folder`, `spam`, `archive`, `submit_to_media_feed`, `add_rule`, `remove_rule`) or rule type applied (e.g. `SPAM`, `ERASE`), or special values: `ERROR`, `OVERSIZE` |
| `detail` | String | no | Extra context — target folder name, `promptName` used, compression factor, failure reason, token-count info, etc. |

Multiple entries are written per email when the LLM calls multiple tools, or a single entry when a rule applies.

### Capacity

- **Billing mode:** `PAY_PER_REQUEST` (on-demand). Volume scales linearly with email volume; pay-per-request avoids capacity tuning.

### Lifecycle

After the daily digest runs successfully:
1. MailKick queries all items with the digest's `date` partition key
2. The digest LLM call is made with these entries as input
3. The digest email is created in Inbox via JMAP
4. **Only after the email is successfully created**, MailKick deletes all queried items in batches via `BatchWriteItem`

If any of these steps fail, the entries remain in DynamoDB and the digest will retry on the next scheduled run.

### TTL (optional)

Items can be given a `ttl` attribute set to e.g. 30 days from `timestamp`, acting as a safety net to clean up entries from days where the digest never ran successfully and were never deleted. This is optional and not required for correct operation.

### Example items

```/dev/null/log-example-1.json#L1-9
{
  "date": "2025-01-15",
  "timestamp": "2025-01-15T08:42:13.001Z",
  "messageId": "<abc123@example.com>",
  "from": "newsletter@example.com",
  "subject": "Your weekly digest",
  "action": "SPAM",
  "detail": "matched rule: sender exact"
}
```

```/dev/null/log-example-2.json#L1-9
{
  "date": "2025-01-15",
  "timestamp": "2025-01-15T09:15:42.502Z",
  "messageId": "<xyz789@bank.com>",
  "from": "statements@bank.com",
  "subject": "January statement available",
  "action": "submit_to_media_feed",
  "detail": "compressionFactor=5"
}
```

```/dev/null/log-example-3.json#L1-9
{
  "date": "2025-01-15",
  "timestamp": "2025-01-15T11:03:21.117Z",
  "messageId": "<huge@example.com>",
  "from": "promo@example.com",
  "subject": "Massive HTML promotion",
  "action": "OVERSIZE",
  "detail": "estimatedTokens=185000 limit=100000"
}
```

---

## IAM Permissions

The IAM user whose keypair is baked into the Docker image (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`) requires the following DynamoDB permissions on both tables in `us-east-2`:

| Action | `mailkick.rules` | `mailkick.log` |
|---|---|---|
| `dynamodb:GetItem` | ✓ | — |
| `dynamodb:Query` | — | ✓ |
| `dynamodb:PutItem` | ✓ | ✓ |
| `dynamodb:DeleteItem` | ✓ | — |
| `dynamodb:BatchWriteItem` | — | ✓ |
| `dynamodb:Scan` | ✓ | — |

`Scan` on `mailkick.rules` is acceptable on a small table.
