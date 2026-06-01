#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_TAR="$SCRIPT_DIR/mailkick-docker/dist/mailkick.tar"

echo "==> Building MailKick..."
"$SCRIPT_DIR/build.sh"

echo "==> Loading MailKick image..."
docker load -i "$IMAGE_TAR"

echo "==> Stopping existing MailKick container (if any)..."
docker stop mailkick 2>/dev/null || true
docker rm mailkick 2>/dev/null || true

echo "==> Starting MailKick..."
docker run -d --restart unless-stopped --name mailkick -p 16080:8080 mailkick:latest
