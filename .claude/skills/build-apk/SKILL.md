---
name: build-apk
description: Build the signed family APK of Finance Tracker from the pushed branch and send it to the owner. Use when the owner asks for a new build/APK, or after pushing an update they should install.
---

# Build the family APK

The build runs in a **separate cloud session**, started from what is pushed to GitHub, so the APK
matches the code exactly. From a coding session: push first, then start a new session (the
`create_session` tool) on `https://github.com/ttinorua/finance-tracker`, branch
`feature/category-memory`, in the same environment, with the steps below as its prompt. Tell it
the commit it should build ("abc1234 or newer") and what changed for the owner.

If you are the build session, do this:

1. Check `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD` are set, **without printing
   values**. If either is missing, stop and say which name.
2. Report yes/no only (never values) for: `GEMINI_API_KEY`, `GROQ_API_KEY`, `FIREBASE_API_KEY`,
   `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`, `FINANCE_APP_ANTHROPIC_API_KEY`,
   `ENABLE_BANKING_PRIVATE_KEY`, `ENABLE_BANKING_PRIVATE_KEY_B64`.
   - GEMINI, GROQ and FIREBASE_* **must be present** (AI features, household budget). That is
     correct, not a problem. If a FIREBASE_* one is missing, stop and say which.
   - `FINANCE_APP_ANTHROPIC_API_KEY`, `ENABLE_BANKING_PRIVATE_KEY`, `ENABLE_BANKING_PRIVATE_KEY_B64`
     are the owner's personal keys and **must be absent**. If any is set, stop and ask the owner
     to remove it.
   - Shape checks without printing: `FIREBASE_API_KEY` starts with `AIza`; `FIREBASE_APP_ID`
     starts with `1:` and contains `:android:`; `FIREBASE_PROJECT_ID` has no spaces.
   - Don't stop to ask for a "go" if all checks pass.
3. If `/opt/android-sdk` is missing: `CLAUDE_CODE_REMOTE=true bash .claude/hooks/session-start.sh`.
   `local.properties` must contain only `sdk.dir=/opt/android-sdk`.
4. `env -u ANTHROPIC_API_KEY ./gradlew testDebugUnitTest` (all must pass; one Robolectric test
   downloads a runtime jar the first time), then `env -u ANTHROPIC_API_KEY ./gradlew assembleRelease`.
   On HTTP 429 from a dependency download, just run the same command again.
   The session-start hook already began fetching and preparing the libraries in the background
   (log: `/tmp/gradle-warmup.log`). **Wait for it to end before the first Gradle command**: run
   `until [ -s /tmp/gradle-warmup.status ]; do sleep 10; done; cat /tmp/gradle-warmup.status` in
   the background (2-hour limit). Running a build alongside it makes both fail. If it says
   `failed` (usually Maven's 429), just run the commands above anyway (rerun on 429).
   Tell the owner how long the build took in total.
   Run each Gradle command in the background with the **maximum** time limit (2 hours), never a
   shorter one, so it isn't killed midway and has to start over. (Code shrinking is off for
   release since Oct 2026: it took over an hour per build. If it's ever turned back on, expect
   `minifyReleaseWithR8` alone to take 1+ hour.)
5. Verify with `apksigner` (`/opt/android-sdk/build-tools/34.0.0`): signer certificate SHA-256 must
   be exactly `e40fb71b074a0b8cabe9615e710ad8b84cabcfc82273298bd7f9b4719ce6758b`. If not, do
   **not** send the APK; tell the owner. Never sign with another key.
6. In `app/build/generated/source/buildConfig/release/com/financetracker/app/BuildConfig.java`:
   `ANTHROPIC_API_KEY` and `ENABLE_BANKING_PRIVATE_KEY_B64` must be empty strings; `GEMINI_API_KEY`,
   `GROQ_API_KEY`, `ONEDRIVE_CLIENT_ID`, `FIREBASE_API_KEY`, `FIREBASE_APP_ID`,
   `FIREBASE_PROJECT_ID` non-empty. Report yes/no only.
7. Copy the APK to the working directory as `FinanceTracker-family.apk` (git-ignored; leave it)
   and send it with SendUserFile (display: attach). Say briefly that it installs as an update and
   keeps all data, what changed, what to test, and the yes/no results from steps 2 and 6.

Never print or commit secrets. Don't change code or push from the build session.
