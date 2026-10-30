# Saved for later

Ideas the owner asked to keep, with what was agreed so far.

## AI advisor: show whether each AI has a key (built Oct 2026)

Settings → AI advisor → AI assistant. Replace the grey line under each AI's name with:

| Situation | Line (✓ green, ✗ grey) |
|---|---|
| Own key saved | ✓ Key set — your own key |
| No own key, built-in key present | ✓ Key set — built into the app |
| No key, free AI | ✗ No key set — skipped until you add one (free to create) |
| No key, paid AI | ✗ No key set — skipped until you add one (optional, paid) |

The "Add key" / "Change" buttons stay as they are. "Key set" means a key is saved, not that it
works; a "Test key" button was offered as a possible follow-up.
Code: `KeyRow` in `app/src/main/java/com/financetracker/app/ui/screens/settings/AiAssistantSettings.kt`.

## Using a Claude Pro or ChatGPT Plus subscription instead of an API key (checked 17 Oct 2026)

**Claude Pro: not possible.** Anthropic's terms (Claude Code "Legal and compliance" page, updated
Feb 2026) say Free/Pro/Max sign-ins may only be used in Claude.ai, the Claude apps and Claude
Code; using them in any other app breaks the Consumer Terms, and Anthropic blocks it on its
servers. The account that signs in risks being suspended. Claude in this app keeps working with
an API key (paid per use, separate from Pro).

**ChatGPT Plus: possible, officially.** "Sign in with ChatGPT" with "Use your ChatGPT plan"
(launched at OpenAI DevDay, 29 Sep 2026) lets Go/Plus/Pro users spend their plan's included usage
in other apps, with per-app weekly caps in ChatGPT → Settings → Usage.
- Open-source tools that run on the user's own device can register themselves at first sign-in
  (no partner approval). Commercial or hosted apps must apply via OpenAI's interest form.
  Whether a phone app like this one counts as such a tool must be confirmed in OpenAI's developer
  docs (developers.openai.com/siwc) before building.
- Sign-in is OAuth with PKCE, redirect to `http://127.0.0.1:<port>/auth/callback`, scope
  `chatgpt.tokens.use.direct`. Access token lasts 1 hour, refresh token 30 days. On a phone: open
  the sign-in page in the browser and catch the redirect with a small server inside the app;
  tokens stored encrypted on the phone (fits "stay in the app").
- Preview limits: no `system` role (use `instructions`), no `temperature`/`max_output_tokens`,
  `store: false` + streaming required, no hosted tools (own function tools work). Errors to
  handle: `subscription_sharing_usage_limit_exceeded` (429), `subscription_sharing_user_not_eligible` (403).
- On Plus, the 5-hour limit is shared with ChatGPT Work/Codex and every other connected app.
- Built Oct 2026 as "ChatGPT (your plan)": `data/ai/chatgpt/` (sign-in, receiver, requests) and
  `data/ai/agent/ChatGptAgentSession.kt`. Default order Gemini → Groq → ChatGPT → Claude.
  Not used for reading documents (receipts, loan papers). Free accounts: not confirmed by OpenAI;
  an ineligible account gets a clear message and the next AI takes over.

Sources: help.openai.com/en/articles/20001542, flaviocopes.com/sign-in-with-chatgpt,
code.claude.com/docs/en/legal-and-compliance.

## Robolectric test download via Google's mirror (done 17 Oct 2026)

The build now fetches libraries from Google's copy of Maven Central (`settings.gradle.kts`), but
`DatabaseUpgradeTest` (Robolectric) still downloads its `android-all` jar straight from Maven
Central and can hit HTTP 429 (it did once on 17 Oct; a rerun passed). Point Robolectric at the
mirror too, e.g. `systemProperty("robolectric.dependency.repo.url",
"https://maven-central.storage-download.googleapis.com/maven2/")` on the unit-test tasks in
`app/build.gradle.kts`, and check the test still passes on a fresh machine.

## "Closed" switch for an account (parked 30 Oct 2026)

The owner is moving to a new main account and may close the old one at the bank. Instead of
deleting it (which removes all its transactions) or merging it (meant for the same account
showing up twice; it would also redirect the old account's bank sync into the new one):
- A "Closed" switch per account in Settings → Accounts, can be switched back ("Reopen").
- A closed account is hidden from the account pickers (top selector, alerts, budgets), but its
  past transactions still show under "All accounts", in Activity, and count in totals, trends
  and averages.
- Bank sync skips a closed account. Shared bills, recurring and salary detection keep using its
  history.
