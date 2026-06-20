#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/mailkick-docker"
DOWNLOAD_DIR="$DOCKER_DIR/download"
DIST_DIR="$DOCKER_DIR/dist"

# ── Validate build.properties ──────────────────────────────────────────────────
if [ ! -f "$SCRIPT_DIR/build.properties" ]; then
    echo "ERROR: build.properties not found."
    echo "       Copy build.properties.template, fill in the values, and try again."
    exit 1
fi

# shellcheck source=/dev/null
. "$SCRIPT_DIR/build.properties"

require_prop() {
    eval _val=\$$1
    if [ -z "$_val" ]; then
        echo "ERROR: $1 is not set in build.properties"
        exit 1
    fi
}

require_prop AWS_REGION
require_prop MAILKICK_SECRET_NAME
require_prop CONFIG_S3_BUCKET
require_prop CONFIG_S3_KEY

# ── Maven build ────────────────────────────────────────────────────────────────
echo "==> Building MailKick..."
cd "$SCRIPT_DIR"
mvn clean package -DskipTests

# ── Fetch secrets from Secrets Manager ────────────────────────────────────────
echo "==> Fetching secrets from AWS Secrets Manager (secret: $MAILKICK_SECRET_NAME)..."
SECRET_JSON=$(aws secretsmanager get-secret-value \
    --secret-id "$MAILKICK_SECRET_NAME" \
    --region "$AWS_REGION" \
    --query SecretString \
    --output text)

# ── Generate credentials.sh ───────────────────────────────────────────────────
echo "==> Generating credentials.sh..."

CREDENTIALS_FILE="$DOWNLOAD_DIR/credentials.sh"

# Write JSON to a temp file to avoid shell-quoting issues when passing to Python.
SECRET_TMP=$(mktemp)
echo "$SECRET_JSON" > "$SECRET_TMP"

# Use Python (shlex.quote) to produce properly shell-quoted export lines.
CREDENTIALS_FILE="$CREDENTIALS_FILE" \
SECRET_TMP="$SECRET_TMP" \
AWS_REGION="$AWS_REGION" \
CONFIG_S3_BUCKET="$CONFIG_S3_BUCKET" \
CONFIG_S3_KEY="$CONFIG_S3_KEY" \
MEDIA_FEED_URL="${MEDIA_FEED_URL:-}" \
python3 << 'PYEOF'
import os, json, shlex

with open(os.environ["SECRET_TMP"]) as f:
    secret = json.load(f)

def export_line(key, val):
    return "export {}={}".format(key, shlex.quote(val))

lines = [
    "#!/bin/sh",
    export_line("FASTMAIL_API_TOKEN",    secret["FASTMAIL_API_TOKEN"]),
    export_line("ANTHROPIC_API_KEY",     secret["ANTHROPIC_API_KEY"]),
    export_line("AWS_ACCESS_KEY_ID",     secret["AWS_ACCESS_KEY_ID"]),
    export_line("AWS_SECRET_ACCESS_KEY", secret["AWS_SECRET_ACCESS_KEY"]),
    export_line("AWS_REGION",            os.environ["AWS_REGION"]),
    export_line("CONFIG_S3_BUCKET",      os.environ["CONFIG_S3_BUCKET"]),
    export_line("CONFIG_S3_KEY",         os.environ["CONFIG_S3_KEY"]),
]

media_feed_url = os.environ.get("MEDIA_FEED_URL", "")
if media_feed_url:
    lines.append(export_line("MEDIA_FEED_URL", media_feed_url))

with open(os.environ["CREDENTIALS_FILE"], "w") as f:
    f.write("\n".join(lines) + "\n")
PYEOF

rm -f "$SECRET_TMP"
chmod +x "$CREDENTIALS_FILE"

# ── Stage JAR and launch.sh ────────────────────────────────────────────────────
echo "==> Staging JAR and launch.sh..."
cp "$SCRIPT_DIR/mailkick-server/target/mailkick-server-1.0.0-SNAPSHOT.jar" \
   "$DOWNLOAD_DIR/mailkick-server-1.0.0-SNAPSHOT.jar"
cp "$DOCKER_DIR/scripts/launch.sh" "$DOWNLOAD_DIR/launch.sh"
chmod +x "$DOWNLOAD_DIR/launch.sh"

# ── Docker build ───────────────────────────────────────────────────────────────
echo "==> Building Docker image..."
docker build -t mailkick:latest "$DOCKER_DIR"

# ── Save image ─────────────────────────────────────────────────────────────────
IMAGE_TAR="$DIST_DIR/mailkick.tar"
echo "==> Saving image to $IMAGE_TAR..."
docker save mailkick:latest -o "$IMAGE_TAR"

rm -f "$CREDENTIALS_FILE"

echo ""
echo "Build complete. Image saved to mailkick-docker/dist/mailkick.tar"
echo ""
