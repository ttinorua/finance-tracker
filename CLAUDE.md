# Working with the owner of this project

## Questions come before code

If a message contains a question mark ("?") or reads like a question, do not start coding.
Answer the question or clarify what's meant first, then wait for an explicit go-ahead before
changing anything.

- Reading code, checking build status or looking things up to answer the question is fine.
  Editing files, committing, pushing or starting a build is not.
- If a message mixes a request with a question (e.g. "Add X. Also, why does Y happen?"),
  answer the question, confirm what you're about to change, and wait for the go-ahead before
  coding any of it.

## Talking to the owner

- Plain, everyday language; short answers. Say what changed for them in the app, not internals.
- Be honest about what was and wasn't verified (tests pass vs. tried on a real account).
- Never ask the owner to paste keys or passwords into the chat.

## The repository is public

- Never print, log or commit a secret value (API keys, keystore, passwords, tokens). Key
  *names* and the public signing fingerprint below are fine.
- Keys reach the app at build time from environment variables (see `app/build.gradle.kts`).

## Building the APK the owner installs ("family build")

Use the `build-apk` skill (`.claude/skills/build-apk/SKILL.md`). In short:
- Build in a **separate cloud session** started from the pushed branch, so the APK matches GitHub.
- Run every Gradle command as `env -u ANTHROPIC_API_KEY ./gradlew …` (the platform may set it).
- Must be **absent**: `FINANCE_APP_ANTHROPIC_API_KEY`, `ENABLE_BANKING_PRIVATE_KEY`,
  `ENABLE_BANKING_PRIVATE_KEY_B64` (the owner's personal keys).
- Must be **present**: `GEMINI_API_KEY`, `GROQ_API_KEY`, `FIREBASE_API_KEY`, `FIREBASE_APP_ID`,
  `FIREBASE_PROJECT_ID`, `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`. Their presence is
  correct, not a problem.
- Sign only with the permanent key: signer SHA-256 must be exactly
  `e40fb71b074a0b8cabe9615e710ad8b84cabcfc82273298bd7f9b4719ce6758b`. Never another key.
- Send it as `FinanceTracker-family.apk`; it installs as an update and keeps the owner's data.

## How work is done

- Branch: `feature/category-memory` on `ttinorua/finance-tracker` (renamed from `ttinorua/test`).
- Before pushing: `env -u ANTHROPIC_API_KEY ./gradlew compileDebugKotlin testDebugUnitTest lintDebug`.
- Commit messages say what changed and why, in plain words.
- Stay in the app: store connections run on the phone (sign-in kept encrypted on the device).
  No separate cloud service or backend holding other people's tokens.

## Store receipts: status and open items

Details of each connection: `docs/store-connectors.md` (full handover, incl. Coop findings: `docs/store-connectors-handover.md`).
- Working: Lidl Plus, Nemlig.com, Netto+, Bilka+, Føtex+, REMA 1000 (Scan Selv purchases only).
- Føtex+ note: an account without Føtex+ consent gets Salling's "Opret bruger" form after the
  password; the app makes it visible (its box was 0 px tall in the app window). Confirmed working
  on the owner's phone (13 Oct): fill in the name, tick all three boxes, press "Opret bruger".
- Last: **Coop** (Lobyco OIDC + MitID via Signicat; MitID may not finish inside an app window).
- Order the owner chose: Netto+ → Bilka+/Føtex+ → REMA 1000 → Coop last.
