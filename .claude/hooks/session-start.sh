#!/bin/bash
# Prepares a fresh Claude Code on the web container to build and test this Android app:
# installs the Android SDK (fresh containers don't have one) and points Gradle at it.
# Build secrets (FINANCE_APP_ANTHROPIC_API_KEY, ENABLE_BANKING_PRIVATE_KEY, ANDROID_KEYSTORE_B64,
# ANDROID_KEYSTORE_PASSWORD) come from the cloud environment's variables — never from here.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK_ROOT=/opt/android-sdk
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  mkdir -p "$SDK_ROOT/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o "$tmp/cmdtools.zip" -d "$tmp"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

if [ ! -d "$SDK_ROOT/platforms/android-34" ] || [ ! -d "$SDK_ROOT/build-tools/34.0.0" ]; then
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null
fi

# local.properties is gitignored; it only tells Gradle where the SDK is.
PROPS="$CLAUDE_PROJECT_DIR/local.properties"
if ! grep -qs '^sdk.dir=' "$PROPS"; then
  echo "sdk.dir=$SDK_ROOT" >> "$PROPS"
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$SDK_ROOT" >> "$CLAUDE_ENV_FILE"
fi

# Download Gradle and the project's plugins now.
cd "$CLAUDE_PROJECT_DIR"
./gradlew --quiet help >/dev/null

# Early fetch: each cloud session starts on a fresh machine, so the app's libraries still have to
# be downloaded and prepared (about 20 minutes of a build). Start that in the background now, so
# it's done or well underway by the time a build or test is asked for, and Gradle reuses the
# result. Don't run a build alongside it (both fail): wait for the status file below. Maven Central sometimes
# answers HTTP 429 (too many requests), so retry with growing pauses. Log: /tmp/gradle-warmup.log.
# When it ends (done or given up), it writes "done" or "failed" to /tmp/gradle-warmup.status;
# builds should wait for that file before starting, so the two don't run over each other.
# No secrets are needed or printed: it only compiles, and converts the libraries for the release APK.
WARMUP_TASKS="compileDebugUnitTestKotlin mergeExtDexRelease"
rm -f /tmp/gradle-warmup.status
nohup setsid bash -c "
  pause=30
  for i in 1 2 3 4 5; do
    if env -u ANTHROPIC_API_KEY ./gradlew --console=plain $WARMUP_TASKS; then
      echo 'warm-up done'; echo done > /tmp/gradle-warmup.status; exit 0
    fi
    echo \"warm-up attempt \$i failed; retrying in \$pause s\"; sleep \$pause; pause=\$((pause * 2))
  done
  echo failed > /tmp/gradle-warmup.status
" >/tmp/gradle-warmup.log 2>&1 < /dev/null &
