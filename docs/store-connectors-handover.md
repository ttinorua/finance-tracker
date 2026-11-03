# Danish store receipt connectors — handover (Lidl Plus, Nemlig.com, Netto+/Bilka+/Føtex+, REMA 1000, Coop)

Status 17 Oct 2026, from the Finance Tracker Android app (Kotlin). Lidl Plus, Nemlig.com, Netto+, Bilka+,
Føtex+ and REMA 1000 work on a real phone. Coop is not built; what is known about it is at the end.

None of these are official APIs. They are what the stores' own apps and websites use, found by inspecting
those apps. They can stop working whenever a store changes them. Use them only for the person's own account
(or with the account holder's consent), and keep the person's sign-in on their own device.

---------------------------------------------------------------------------------------------------------

## 1. Design that worked

- **Everything runs on the user's device.** The person signs in on the store's own login page (shown in an
  in-app WebView), or types e-mail/password into the app for stores whose login page can't run in a WebView.
  The app keeps only what the store hands back (refresh token, tokens, or session cookie), encrypted on the
  device (Android Keystore). Passwords are never stored. No backend server holds anyone's store tokens.
- **One interface per store** (Kotlin, simplified):
  ```kotlin
  enum class LoginMode { WEBVIEW, CREDENTIALS }
  interface StoreConnector {
      val loginMode: LoginMode                       // WEBVIEW (default) or CREDENTIALS (Nemlig)
      val needsEmailFirst: Boolean                   // Salling: e-mail must be in the login URL
      fun loginUrl(emailHint: String?): String       // page to open in the WebView
      fun captureFromUrl(url: String): String?       // called for every URL the WebView is about to open;
                                                     // returns the one-time code at the redirect, else null
      fun fillPasswordScript(password: String): String?  // JS that fills the password into the store page
      fun loginErrorScript(): String?                // JS that detects "wrong password" on the page
      fun stuckPageScript(): String?                 // JS to re-show a hidden step (Salling/Føtex+)
      suspend fun loginWithCredentials(email: String, password: String): String  // CREDENTIALS only
      suspend fun finishLogin(captured: String): String   // code -> stored sign-in (tokens as JSON)
      suspend fun fetch(secret: String, since: Long): Pair<List<StoreReceipt>, String>
                                                     // receipts since a date + the (possibly renewed) sign-in
  }
  ```
- **Refresh tokens rotate** at Lidl, Salling and (possibly) REMA: once renewed, the old one stops working.
  So: (a) never run two fetches for the same account at once; (b) if a fetch fails *after* the token was
  renewed, still save the new token (we throw `StoreFetchFailed(newSecret, cause)` and the caller saves it);
  (c) Salling/REMA: reuse the access token while valid, renew only when needed.
- **Receipt model** (what every connector returns):
  `storeName, purchasedAt (epoch ms), total, discount, receiptNumber, items[name, quantity, amount (paid,
  after discount), discount]` plus optional per-item category hints. `receiptNumber` is `store:<id>` (e.g.
  `lidl:…`, `salling:…`, `rema:…`, `nemlig:…`) and is used to never import the same receipt twice.
- **Amounts** come as numbers or Danish strings ("12,95"); parse both. Dates are ISO 8601 or epoch
  seconds/millis; treat local times as Europe/Copenhagen.
- **WebView login tips**: clear cookies AND WebStorage before each login (a session from another chain on the
  same login service can break the next one); intercept the redirect URL in `shouldOverrideUrlLoading`
  (custom schemes like `com.lidlplus.app://callback` would otherwise fail); report a page that stays blank
  (URL, a page identifier, first visible words) — that's how the Føtex+ problem was found.
- **Consent**: we show a "what happens" screen and store a consent record (store, time, text version,
  own/partner account) before connecting.

---------------------------------------------------------------------------------------------------------

## 2. Lidl Plus (Denmark) — works

- OAuth 2 + PKCE (S256) on Lidl's own login page.
- Authorize (open in WebView):
  `https://accounts.lidl.com/connect/authorize?client_id=LidlPlusNativeClient&response_type=code`
  `&scope=openid%20profile%20offline_access%20lpprofile%20lpapis&redirect_uri=com.lidlplus.app%3A%2F%2Fcallback`
  `&code_challenge=<S256>&code_challenge_method=S256&Country=DK&language=da-DK`
- Redirect: `com.lidlplus.app://callback?code=…` (catch it in the WebView).
- Token: `POST https://accounts.lidl.com/connect/token`, form-encoded, header
  `Authorization: Basic base64("LidlPlusNativeClient:secret")` (that literal value, as in the public
  open-source lidl-plus projects), `Accept: application/json`.
  - code: `grant_type=authorization_code&code=…&redirect_uri=com.lidlplus.app://callback&code_verifier=…`
  - renew: `grant_type=refresh_token&refresh_token=…` → new `access_token` + **new `refresh_token`** (rotates).
  - 400/401 → sign in again.
- Receipts (User-Agent `okhttp/5.4.0`). All of these headers are required, else 401:
  `Authorization: Bearer <access>`, `App-Version: 16.43.4`, `Operating-System: Android`,
  `App: com.lidl.eci.lidlplus`, `Accept-Language: da`, `Accept: application/json`, `OS-Version: 14`,
  `Model: Pixel 7`, `Brand: Google`, `deviceid: <any fixed 16-hex id>`, `country: DK`.
  - List: `GET https://tickets.lidlplus.com/api/v2/DK/tickets?pageNumber=<n>&onlyFavorite=false`
    → `{ tickets:[{id, date, …}], size, totalCount }` (newest first; stop when older than wanted).
  - Detail: `GET https://tickets.lidlplus.com/api/v3/DK/tickets/<id>`
    → `date, totalAmount (or total), totalDiscount, store{name}, itemsLine[{name|description, quantity,
    originalAmount | currentUnitPrice×quantity, discounts[{amount}]}]`. Item paid = original − sum(discounts).
- Source: open-source "lidl-plus" projects (Python/Node) + checks against the app.

---------------------------------------------------------------------------------------------------------

## 3. Nemlig.com — works (online orders)

- Its website login (a pop-up behind a waiting room) does not work in a WebView, so e-mail + password are
  typed in the app and used once, the way the website does it. Keep only the session cookies (the
  `.ASPXAUTH` cookie lasts about a year). Keep and resend all cookies; keep a fixed browser User-Agent.
- Common headers: `Accept: application/json, text/plain, */*`, `Device-Size: desktop` (login) / `mobile`
  (fetch), `Platform: web`, `Version: 11.201.0`, `X-Correlation-Id: <new UUID per request>`.
- Login (base `https://www.nemlig.com`):
  1. `GET /webapi/AntiForgery` → `{Value}` (or the `XSRF-TOKEN` cookie) = xsrf.
  2. `GET /webapi/Token` → `{access_token}` = short-lived bearer.
  3. `POST /webapi/login` JSON `{"Username":…, "Password":…, "CheckForExistingProducts":true,
     "DoMerge":true, "AppInstalled":false, "SaveExistingBasket":false}` with headers `X-XSRF-TOKEN: <xsrf>`,
     `Authorization: Bearer <token>`, `Referer: https://www.nemlig.com/login?returnUrl=%2F`.
     400/401 = wrong e-mail/password. Success = `.ASPXAUTH` cookie set.
- Fetch (with the saved cookies): repeat AntiForgery + Token, then with `Authorization: Bearer` + `X-XSRF-TOKEN`:
  - `GET /webapi/order/GetBasicOrderHistory?skip=<n>&take=10` → `{Orders:[{Id, OrderNumber, Status,
    HasInvoice, Total, OrderDate, DeliveryTime{Start}}]}` (no `Orders` = not signed in).
  - Charged orders: `Status == 4` (delivered) or `HasInvoice == true`. Status 1/2 = still pending.
  - Use `DeliveryTime.Start` as the date (that's when the card is charged), else `OrderDate`.
  - `GET /webapi/v2/order/GetOrderHistory/<Id>` → `Lines[{ProductName, Quantity, Amount, OriginalAmount,
    RefundedAmount, MainGroupName, GroupName}]`. Paid = Amount − RefundedAmount; discount = Original − paid.
    The delivery fee = order Total − sum of lines.
  - 401/403 → sign in again. Save the cookies again after each fetch (the site renews them).
- Source: open-source "nemlig_cli" notes + checks against the website.

---------------------------------------------------------------------------------------------------------

## 4. Salling Group: Netto+, Bilka+, Føtex+ — all three work

One implementation, one "tenant" per chain:

| Chain  | tenantId | X-tenantAlias | token host |
|--------|----------|---------------|------------|
| Netto  | 4        | TID-2Y7JRG    | https://idp.dsgapps.dk |
| Bilka  | 2        | TID-BZ929S    | https://idp.dsgapps.dk |
| Føtex  | 3        | TID-F86K6Y    | https://p-idp.dsgapps.dk (as the Føtex+ app; same service) |

- Login page (open in WebView; the e-mail must be in the URL, so ask for it first):
  `https://p-idp.dsgapps.dk/apps?clientId=customer-program&tenantId=<tenantId>&channel=CustomerProgram`
  `&clientFlow=gigya&nonce=<random>&clientTraceId=<random>&emailOrPhone=<url-encoded e-mail>`
  `&code_challenge_method=S256&code_challenge=<S256>`
- The page is a Gigya login form. Typing in its password box is broken on some phones (cursor jumps to the
  start), so type the password in the app and inject it: in `#gigya-login-screen`, wait until
  `input[name="username"]` has a value and `input[name="password"]` is visible, set the value, dispatch
  `input`/`keyup`/`change`, click `input[type="submit"]`.
- Wrong password: an element `.gigya-error-msg-active` / `.gigya-error-display-active` whose class contains
  `403042` ("Ugyldig login eller adgangskode").
- Redirect: `http://localhost/#…code=…` (code in the fragment, sometimes the query). Catch it in the WebView.
- Token: `POST <token host>/token`, form-encoded, `Accept: application/json`, User-Agent `okhttp/4.12.0`:
  - code: `grant_type=authorization_code&code=…&code_verifier=…&client_id=customer-program`
  - renew: `grant_type=refresh_token&refresh_token=…&client_id=customer-program`
  - Response fields: `access_token|accessToken`, `id_token|idToken`, `refresh_token|refreshToken`,
    `expires_in|expiresIn` (else read `exp` from the JWT). Refresh token rotates.
    400 with `invalid_grant` → sign in again.
- Backend `https://p-club.dsgapps.dk`, headers (all needed, else 401):
  `Authorization: Bearer <access (or id) token>`, `Accept: application/json`,
  `X-ClientAppName: CustomerProgram-android`, `X-DevicePlatform: android`, `X-DeviceOS: android`,
  `X-DeviceOSVersion: 14`, `X-DeviceName: Pixel 7`, `X-DeviceBrand: Google`, `X-CommitID: d2e5bbeda`,
  `X-AppVersion: 2.9.5.1090`, `X-ClientVersion: 2.9.5.1090`, `X-ClientTraceId: <install UUID>`,
  `X-OperationTraceId: OT-<random>`, `X-tenantAlias: <alias>`, `x-id_token: Bearer <id token>`,
  `x-idpid: <"sub" claim of the id token>`.
  - List: `GET /api/cp/receipt` → `{receiptsList:[{receipts:[{id, type|itemType, postingDate (epoch s or
    ms), storeName, salesTotal}]}]}`. If it answers 401 or a body containing "Expired": renew and retry once.
  - Detail: `GET /api/cp/receipt/details?receiptId=<id>&type=<type>` → array; first element:
    `storeName, postingDate, salesTotal, discountTotal, lineItems[{articleDescription, salesTotal |
    salesPrice, qtyInSalesUnit | qtyInBaseUnit, discountTotal}]`.
- **Føtex+ gotcha (solved):** an account without Føtex+ membership/consent gets Salling's "Opret bruger" step
  (`body[data-page="gigya-complete-registration-screen"]`) after the password. In a WebView it rendered 0 px
  tall because Salling's CSS sets `html, body, #gigya-container { height:100% }` and `#gigya-container
  { overflow:auto }`. Fix: inject the style `html, body, #gigya-container { height:auto !important;
  min-height:100%; overflow:visible !important; }`, and if the step is still hidden, re-show it with the
  page's own `IDP.API.gigya.showScreenSet({screenSet:'CP-Login', startScreen:'gigya-complete-registration-screen'})`.
  The person fills in the name, ticks the three boxes, presses "Opret bruger", and the page continues to the
  `http://localhost/#code=…` redirect. Confirmed on a real phone.
- Source: own inspection of the Netto+/Bilka+/Føtex+ apps and Salling's login page scripts
  (`client-flow-gigya.js`).

---------------------------------------------------------------------------------------------------------

## 5. REMA 1000 — works (Scan Selv purchases only)

- Only purchases made with the Scan Selv app have digital receipts; normal checkout purchases aren't linked
  to the account.
- Login (WebView). Start at the authorize URL, NOT the login page directly (that ends in "An error occurred
  while redirecting"):
  `https://cphapp.rema1000.dk/api/oauth2/authorize?client_id=scan-and-pay&redirect_uri=dk.rema1000.app%3A%2F%2Flogincallback`
  `&state=&code_challenge=<S256>&code_challenge_method=S256&scope=&response_type=code&theme=dark&lang=en`
  `&prompt=login&hide_login_terms=true&screen=login`
- Redirect: `dk.rema1000.app://logincallback?code=…`
- Every API request carries: `X-App-Platform: Android`, `X-App-Version: 1.8.15`, `X-App-Language: da`,
  `X-App-DeviceId: <random UUID, kept per account>`; User-Agent `okhttp/4.12.0`.
- Tokens (base `https://apim-rema-scan-prod.azure-api.net/v1`), JSON bodies:
  - `POST /token/new` `{"code":…, "codeVerifier":…, "redirectUri":"dk.rema1000.app://logincallback"}`
  - `POST /token/renew` `{"refreshToken":…}` (400/401 → sign in again)
  - Response (maybe wrapped in `data`): `access_token|accessToken`, `refresh_token|refreshToken`,
    `expires_in|expiresIn` (else JWT `exp`).
  - REMA's login-server token (`cphapp…/oauth/token`) is NOT accepted by the receipts API ("ERROR_42").
- Receipts: `GET /v1/user/receipts` (optionally a since-date parameter; the exact name isn't confirmed — we try
  none, `sinceDate`, `since`, `fromDate`, `from` in ISO/day/epoch forms and remember the one that works),
  `Authorization: Bearer <access>`.
  - Fields: `id|displayId|receiptId`, `paymentDate` (or purchaseDate/date), `totalPrice` (+ `totalPriceString`
    "181,68"), `totalDiscount`, `store{name}`, items in `items|receiptItems|lines|products` with
    `shelfText1` (product name as printed; `shelfText2` is an extra shelf line — skip it), `price`,
    `quantity`, `discount`, `deposit` (pant), sometimes a `prices[]` array per line, `sortingDate` (scan order).
  - **Amounts are øre as whole numbers** (181,68 kr. = 18168). Use `totalPriceString` to confirm the scale.
  - Whether a line's price is before/after discount and with/without deposit isn't confirmed; pick the reading
    whose sum matches the receipt total.
- Source: own inspection of Scan Selv 1.8.15 (RemaService, AuthService, CustomerBackends, RestClientFactory,
  TokensFromCodeRequestAPIModel, TokenData) and PCAPdroid.

---------------------------------------------------------------------------------------------------------

## 6. Coop (Kvickly, SuperBrugsen, Dagli'Brugsen, Fakta, Coop 365, Irma) — NOT built

### What is known
- Coop's app is `dk.coop.coopplus` ("Coop – Scan & Pay, App offers"), built by **Lobyco** (Coop-owned). The
  app saves a copy of every receipt, so receipts exist per account.
- Login is **Lobyco OIDC** with **MitID** (via Signicat). Client id seen: `coop-medlem`.
  API host: `https://api.coopdk.lobyco.net`. Auth host: `https://auth.coopdk.lobyco.net`.
- The auth server's public discovery document
  (`https://auth.coopdk.lobyco.net/.well-known/openid-configuration`, fetched 17 Oct 2026) says (Ory
  Hydra-style):
  - authorize `https://auth.coopdk.lobyco.net/oauth2/auth`
  - token `https://auth.coopdk.lobyco.net/oauth2/token`
  - **device authorization** `https://auth.coopdk.lobyco.net/oauth2/device/auth`
    (grant `urn:ietf:params:oauth:grant-type:device_code` is supported)
  - revoke `/oauth2/revoke`, userinfo `/userinfo`, JWKS `/.well-known/jwks.json`, logout `/oauth2/sessions/logout`
  - PKCE `S256` (and `plain`), response modes query/fragment/form_post, scopes `openid offline offline_access`,
    token auth methods include `none` (public clients possible), ID tokens RS256.
- An old community library (KristofferK/coop-receipt-manager, C#, last commit 2018) used coop.dk with
  username/password. It is obsolete since the move to MitID/Lobyco — don't rely on it.

### Main risk
MitID may not complete inside an in-app WebView (MitID app switching / app-to-app return). That's why Coop
was left for last.

### Ways to try, in order
1. **Device authorization flow** (best fit for MitID): `POST /oauth2/device/auth` with `client_id=coop-medlem`,
   `scope=openid offline_access` → `device_code`, `user_code`, `verification_uri(_complete)`. Open the
   verification URL in the phone's real browser (Chrome Custom Tab), where MitID works normally; poll
   `POST /oauth2/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=…&client_id=coop-medlem`.
   Unknown: whether the `coop-medlem` client is allowed to use this grant. Test this first.
2. **Authorization code + PKCE in a Chrome Custom Tab** (not a WebView), redirect back to the app via the Coop
   app's own redirect URI (must be found in the Coop app — not known yet) or the client's registered one.
3. **WebView** like the other stores, intercepting the redirect — only if MitID completes there.

### Still to find (inspect the Coop app, e.g. jadx + PCAPdroid/mitmproxy, as was done for REMA/Salling)
- The exact `redirect_uri`, scopes and any extra authorize parameters (e.g. `acr_values` for MitID, `prompt`,
  `ui_locales`) the app sends; whether `coop-medlem` is a public client (no secret).
- Receipt endpoints on `api.coopdk.lobyco.net`: list (with paging/date filter) and detail, required headers
  (app version, platform, device id, any API key/subscription header), and the JSON fields (date, total,
  discount, store name, line items, member discounts/bonus, deposit). Check whether amounts are øre.
- Token lifetimes and whether the refresh token rotates.
- Whether a Coop account without membership has receipts (the app says it's for non-members too).

---------------------------------------------------------------------------------------------------------

## 7. Lessons that saved time
- Inspect the store's own app (jadx for code, PCAPdroid or mitmproxy for traffic) to get exact URLs, headers
  and body field names; most 401s were a missing app header.
- Start login where the app starts it (REMA's authorize URL, not the login page).
- Keep the user's sign-in on their device, encrypted; never send it to a server.
- Report the server's own error text (status + a short snippet) in error messages; it made every fix faster.
- Store-side changes can break a connector at any time: show the person a clear "sign in again" / "couldn't
  fetch" message rather than silently returning zero receipts.
