#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_TASK="${ANDROID_BUILD_TASK:-assembleDebug}"
APK_PATH_OVERRIDDEN=0
if [[ -n "${APK_PATH:-}" ]]; then
  APK_PATH_OVERRIDDEN=1
fi
APK_PATH="${APK_PATH:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
FIREBASE_PROJECT="${OLAUNCHER_FIREBASE_PROJECT:-${FIREBASE_PROJECT:-}}"
FIREBASE_APP_ID="${OLAUNCHER_FIREBASE_APP_ID:-${FIREBASE_APP_ID:-}}"
FIREBASE_GROUPS="${OLAUNCHER_FIREBASE_GROUPS:-${FIREBASE_GROUPS:-}}"
FIREBASE_TESTERS="${OLAUNCHER_FIREBASE_TESTERS:-${FIREBASE_TESTERS:-}}"
FIREBASE_RELEASE_NOTES="${OLAUNCHER_FIREBASE_RELEASE_NOTES:-${FIREBASE_RELEASE_NOTES:-}}"
FIREBASE_RELEASE_NOTES_FILE="${OLAUNCHER_FIREBASE_RELEASE_NOTES_FILE:-${FIREBASE_RELEASE_NOTES_FILE:-}}"

if [[ -z "$FIREBASE_PROJECT" || -z "$FIREBASE_APP_ID" ]]; then
  cat >&2 <<'EOF'
Missing Firebase App Distribution configuration.

Set:
  OLAUNCHER_FIREBASE_PROJECT or FIREBASE_PROJECT
  OLAUNCHER_FIREBASE_APP_ID or FIREBASE_APP_ID

The debug APK package is app.olauncher.debug, so the Firebase Android app must
be registered for that package.
EOF
  exit 1
fi

if [[ -z "$FIREBASE_GROUPS" && -z "$FIREBASE_TESTERS" ]]; then
  echo "Set OLAUNCHER_FIREBASE_GROUPS/FIREBASE_GROUPS or OLAUNCHER_FIREBASE_TESTERS/FIREBASE_TESTERS before distributing." >&2
  exit 1
fi

if [[ "$BUILD_TASK" != "assembleDebug" && "$APK_PATH_OVERRIDDEN" != "1" ]]; then
  echo "ANDROID_BUILD_TASK=$BUILD_TASK requires an explicit APK_PATH so the uploaded artifact matches the selected build." >&2
  exit 1
fi

TEMP_SERVICE_ACCOUNT=""
cleanup() {
  if [[ -n "$TEMP_SERVICE_ACCOUNT" && -f "$TEMP_SERVICE_ACCOUNT" ]]; then
    rm -f "$TEMP_SERVICE_ACCOUNT"
  fi
}
trap cleanup EXIT

if [[ -n "${FIREBASE_SERVICE_ACCOUNT_JSON:-}" && -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
  TEMP_SERVICE_ACCOUNT="$(mktemp)"
  chmod 600 "$TEMP_SERVICE_ACCOUNT"
  printf '%s' "$FIREBASE_SERVICE_ACCOUNT_JSON" > "$TEMP_SERVICE_ACCOUNT"
  export GOOGLE_APPLICATION_CREDENTIALS="$TEMP_SERVICE_ACCOUNT"
fi

if [[ -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" && -z "${FIREBASE_TOKEN:-}" && "${FIREBASE_ALLOW_LOCAL_LOGIN:-0}" != "1" ]]; then
  cat >&2 <<'EOF'
No Firebase service-account credentials were provided.

Set GOOGLE_APPLICATION_CREDENTIALS or FIREBASE_SERVICE_ACCOUNT_JSON. To use an
already-authenticated local Firebase CLI account intentionally, rerun with:
  FIREBASE_ALLOW_LOCAL_LOGIN=1
EOF
  exit 1
fi

if [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" && ! -s "$GOOGLE_APPLICATION_CREDENTIALS" ]]; then
  echo "GOOGLE_APPLICATION_CREDENTIALS is missing or empty: $GOOGLE_APPLICATION_CREDENTIALS" >&2
  exit 1
fi

if [[ "${FIREBASE_REUSE_APK:-0}" != "1" ]]; then
  (cd "$REPO_ROOT" && ./gradlew "$BUILD_TASK")
else
  echo "Reusing APK at $APK_PATH. Set FIREBASE_REUSE_APK=0 to rebuild."
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at $APK_PATH" >&2
  exit 1
fi

ARGS=(
  appdistribution:distribute
  "$APK_PATH"
  --project "$FIREBASE_PROJECT"
  --app "$FIREBASE_APP_ID"
)

if [[ -n "$FIREBASE_GROUPS" ]]; then
  ARGS+=(--groups "$FIREBASE_GROUPS")
fi

if [[ -n "$FIREBASE_TESTERS" ]]; then
  ARGS+=(--testers "$FIREBASE_TESTERS")
fi

if [[ -n "$FIREBASE_RELEASE_NOTES_FILE" ]]; then
  if [[ ! -f "$FIREBASE_RELEASE_NOTES_FILE" ]]; then
    echo "Release notes file not found: $FIREBASE_RELEASE_NOTES_FILE" >&2
    exit 1
  fi
  ARGS+=(--release-notes-file "$FIREBASE_RELEASE_NOTES_FILE")
elif [[ -n "$FIREBASE_RELEASE_NOTES" ]]; then
  ARGS+=(--release-notes "$FIREBASE_RELEASE_NOTES")
else
  ARGS+=(--release-notes "Olauncher focus build $(git -C "$REPO_ROOT" rev-parse --short HEAD)")
fi

if [[ -n "${FIREBASE_TOKEN:-}" ]]; then
  ARGS+=(--token "$FIREBASE_TOKEN")
fi

echo "Uploading $APK_PATH to Firebase App Distribution..."
echo "Firebase project: $FIREBASE_PROJECT"
echo "Firebase app: $FIREBASE_APP_ID"

npx --yes firebase-tools@latest "${ARGS[@]}"
