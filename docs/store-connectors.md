# Store connections (receipts from the stores' own accounts)

All run on the phone (`app/src/main/java/com/financetracker/app/data/stores/`). The person signs in;
the app keeps only the store's sign-in (tokens or cookie), encrypted on the device. None of these
are official APIs: they are what the stores' own apps use, found by the owner's inspection of those
apps, and can stop working if a store changes them.

Shared pieces:
- `StoreConnector.kt`: the interface (login window or typed credentials, capture, fetch).
- `StoreSync.kt`: fetches after each bank sync, one at a time (a renewal must not run twice at
  once). A fetch that fails after renewing a sign-in throws `StoreFetchFailed` with the new
  sign-in, which is saved (the old refresh token no longer works once renewed).
- `ui/screens/receipts/StoreScreens.kt`: intro → consent → login → finishing. The login window
  (`StoreLoginWebView`) catches the return address, can fill a password typed in the app, watches
  for a turned-down sign-in, and reports a page that stays blank or an address it can't follow.
- Receipts are deduplicated by `receiptNumber` (e.g. `rema:<id>`): a receipt is never fetched
  twice, so fixing how a receipt is read needs Disconnect → "Delete receipts" → connect again.

## Lidl Plus (`LidlPlus.kt`) — works
OAuth + PKCE on Lidl's own page (`accounts.lidl.com`, client `LidlPlusNativeClient`, return
address `com.lidlplus.app://callback`). Receipts: `tickets.lidlplus.com`. Refresh tokens rotate.

## Nemlig.com (`Nemlig.kt`) — works
Its login can't run in an app window, so e-mail and password are typed in the app and used once
to get the `.ASPXAUTH` session cookie (kept, valid about a year); the password isn't stored.
Orders come from the website's order history (delivered orders).

## Netto+, Bilka+, Føtex+ (`Salling.kt`) — all three work
Salling Group's login (`p-idp.dsgapps.dk/apps`, client `customer-program`, OAuth + PKCE, Gigya
login form). One class, one tenant per chain:

| Chain  | tenantId | X-tenantAlias | token address |
|--------|----------|---------------|---------------|
| Netto  | 4        | TID-2Y7JRG    | idp.dsgapps.dk/token |
| Bilka  | 2        | TID-BZ929S    | idp.dsgapps.dk/token |
| Føtex  | 3        | TID-F86K6Y    | p-idp.dsgapps.dk/token (as the Føtex app; same service) |

- The login page needs the e-mail in the address (`emailOrPhone`), so the app asks for e-mail and
  password first. The password box on Salling's page moves the cursor to the start on every key
  press (its own script), so the password is typed in the app and filled into the page, then
  Salling's login button is pressed. A wrong password shows only as a small Danish line
  (error 403042); the app notices it and goes back to its form.
- Return address `http://localhost/#…code=…`; code swapped at `/token` (authorization_code).
- Receipts: `p-club.dsgapps.dk/api/cp/receipt` and `/api/cp/receipt/details`, with the app's
  headers (`Authorization`, `x-id_token`, `x-idpid`, `X-tenantAlias`, …). Refresh tokens rotate.
- **Føtex+ (solved 13 Oct, kept for the history):** login page and wrong-password check work (tested in a browser), but after
  a correct password the window went blank. Salling uses the same default return address for all
  tenants. A first "blank page" check (no text at all) never fired on the owner's phone, so the
  window now reports when, 15 s after the password, there's nothing to fill in or press (or a
  minute passes, or a page fails to load): address, Salling's screen name (`body[data-page]`) and
  the first visible words. That check also never fired: the owner's screenshot (12 Oct) shows an
  almost empty page (a white bar at the top), and the check only started once the password was in
  Salling's form, so the form most likely never showed. The window now watches from the start
  ("login form didn't show"), and starting a login clears the pages' stored data (WebStorage) as
  well as cookies, in case a Netto/Bilka session left there stops Føtex's page.
  **Found (13 Oct):** the report said `screen gigya-complete-registration-screen`, text "Opret
  bruger · Navn · Jeg vil gerne være medlem af føtex Plus". The password is accepted, but the
  owner's Salling account has no Føtex+ membership yet, so Salling asks to join (name, profile and
  Føtex+ terms checkboxes). In a desktop test browser that form shows normally; in the app window
  only its white top bar shows and the fields count as not visible. Simplest way past it: join
  Føtex+ once in the Føtex app (same Salling login), then the login skips this form.
  That didn't help: after joining in the Føtex app the page still asked. Salling's page looks for
  `data.customerprogram.cpfotMembershipNr` or `preferences.profiling.dk.foetex.customerprogram`
  consent; the app's sign-up evidently doesn't set them. So the window now re-shows the step once
  (`Salling.stuckPageScript`, via the page's own `IDP.API.gigya.showScreenSet`) when it's on
  screen but hidden; after the person submits it, the page carries on to the code. Tested only in
  a desktop browser with the step removed by hand. If it stays hidden, the report adds how the
  step looked (display/visibility/opacity/position).
  The phone's report (13 Oct): step and name field visible and in place, but `#gigya-container`
  0 px tall. Salling's gigya.css sets `html, body, #gigya-container { height: 100% }` and
  `#gigya-container { overflow: auto }`; in the app window that resolved to 0, clipping the step.
  The Salling scripts now add a style (`FIT_FRAME`: height auto, overflow visible) so the box
  grows with the form. Checked in a desktop browser with the box forced to 0 px, then confirmed
  on the owner's phone: the form showed, was submitted, and Føtex+ connected.
  After a correct password Salling's page (`client-flow-gigya.js`) reads the account; with no
  Føtex+ membership number and no Føtex consent it switches to `gigya-complete-registration-screen`
  (join Føtex+), otherwise it checks the Gigya JWT at `/signatureCheckGigyaJWT` and posts the form,
  which ends at `http://localhost/#code=…`. A failed JWT check shows an "ERROR / Trace id" popup.

## REMA 1000 (`Rema1000.kt`) — works (Scan Selv purchases only)
Only purchases made with the Scan Selv app have digital receipts; ordinary checkout purchases
aren't tied to an account.
- Login: start at `https://cphapp.rema1000.dk/api/oauth2/authorize?client_id=scan-and-pay&…`
  (PKCE S256, `redirect_uri=dk.rema1000.app://logincallback`). It sets REMA's session cookie and
  forwards to `login.rema1000.dk`; opening the login page directly ends in "An error occurred
  while redirecting".
- Tokens: `POST https://apim-rema-scan-prod.azure-api.net/v1/token/new` with JSON
  `{code, codeVerifier, redirectUri}`; renew at `/v1/token/renew` with `{refreshToken}`. Response
  `access_token`, `refresh_token`. REMA's login-server token (`cphapp…/oauth/token`) is NOT
  accepted for receipts ("ERROR_42 Malformed request").
- Every request carries `X-App-Platform: Android`, `X-App-Version: 1.8.15`, `X-App-Language`,
  `X-App-DeviceId` (a random id the app keeps). Receipts also `Authorization: Bearer <access>`.
- Receipts: `GET /v1/user/receipts?since=…` (only `v1` exists). Amounts are in **øre** as whole
  numbers (181,68 kr. = 18168); item name is `shelfText1` (`shelfText2` is an extra shelf-label
  line); items are sorted by `sortingDate` (scan order, as on REMA's receipt).
- Source of these details: the owner's look at Scan Selv 1.8.15 (`RemaService`, `AuthService`,
  `CustomerBackends`, `RestClientFactory`, `TokensFromCodeRequestAPIModel`, `TokenData`) and
  PCAPdroid (server name).

## Coop — not built (last on the list)
Lobyco OIDC (`auth.coopdk.lobyco.net`, API `api.coopdk.lobyco.net`, client `coop-medlem`) with
MitID via Signicat. Risk: MitID may not complete inside an app window.
