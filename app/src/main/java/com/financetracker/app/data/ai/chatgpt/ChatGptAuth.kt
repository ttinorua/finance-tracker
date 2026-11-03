package com.financetracker.app.data.ai.chatgpt

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.backup.DeviceSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * "Sign in with ChatGPT" with the "Use your ChatGPT plan" permission, so AI requests can run on the
 * person's ChatGPT plan instead of an API key. OpenAI's flow for open-source apps that run on the
 * user's own device: the phone's browser opens OpenAI's sign-in page, and the answer comes back to
 * a small receiver inside the app on 127.0.0.1 ([LoopbackReceiver]). The first sign-in registers
 * the app with the person's account; the issued client id is reused afterwards.
 *
 * Everything stays on the phone: tokens are encrypted with this phone's Android Keystore key and,
 * like the AI keys, are never written into a backup. Docs: developers.openai.com/siwc.
 */
object ChatGptAuth {

    private const val AUTH = "https://auth.openai.com"
    private const val ISSUER = "https://auth.openai.com"
    private const val AUTHORIZE_URL = "$AUTH/api/accounts/authorize"
    private const val TOKEN_URL = "$AUTH/api/accounts/oauth/token"
    private const val REVOKE_URL = "$AUTH/api/accounts/oauth/revoke"
    private const val JWKS_URL = "$AUTH/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val APP_NAME = "Finance Tracker"
    private const val PREFERRED_PORT = 1455
    private const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000
    private const val NETWORK_RETRY_MS = 3 * 60 * 1000L

    /** Where the person sees and caps what each app uses of their ChatGPT plan. */
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_SESSION = "chatgpt_session"
    private const val KEY_REGISTRATION = "chatgpt_registration"
    private const val KEY_HOST_ID = "chatgpt_host_id"
    private const val KEY_WELCOMED = "chatgpt_welcomed"

    /** Phone-specific: never written into a backup, kept on this phone when one is restored. */
    val SECRET_KEYS: Set<String> = setOf(KEY_SESSION, KEY_REGISTRATION, KEY_HOST_ID, KEY_WELCOMED)

    /** [email] when connected with plan use allowed; [problem] explains why it isn't, if known. */
    data class Status(val email: String? = null, val problem: String? = null)

    sealed interface SignIn {
        data object Idle : SignIn
        data object Waiting : SignIn
        /** The browser came back; the app is finishing the sign-in with OpenAI. */
        data object Finishing : SignIn
        /** [firstTime]: show the one-time "You're using your ChatGPT plan" message. */
        data class Done(val email: String, val firstTime: Boolean) : SignIn
        data class Failed(val message: String) : SignIn
    }

    private class Registration(val clientId: String, val subject: String, val email: String?)

    private class Session(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val expiresAt: Long,
        val scopes: Set<String>
    )

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var signInJob: Job? = null
    @Volatile private var receiver: LoopbackReceiver? = null
    /** Counts sign-in attempts, so an old (cancelled) one never overwrites a newer one's state. */
    @Volatile private var attempt = 0

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        updateStatus(problem = null)
    }

    /** The account the app may use the plan of, or null. */
    val connectedEmail: String? get() = _status.value.email

    val isConnected: Boolean get() = connectedEmail != null

    // ---- Sign-in ----

    /** Opens ChatGPT's sign-in page in the browser and waits (in the background) for the answer. */
    fun startSignIn(context: Context) {
        cancelSignIn()
        val app = context.applicationContext
        val mine = ++attempt
        _signIn.value = SignIn.Waiting
        signInJob = scope.launch {
            val result = try {
                runSignIn(app, mine)
            } catch (e: Exception) {
                SignIn.Failed(friendly(e))
            }
            if (mine == attempt) _signIn.value = result
        }
    }

    /** Stops waiting for the browser (the person closed it or changed their mind). */
    fun cancelSignIn() {
        attempt++
        receiver?.close()
        receiver = null
        signInJob?.cancel()
        signInJob = null
        if (_signIn.value is SignIn.Waiting || _signIn.value is SignIn.Finishing) _signIn.value = SignIn.Idle
    }

    fun clearSignInMessage() {
        if (_signIn.value !is SignIn.Waiting && _signIn.value !is SignIn.Finishing) _signIn.value = SignIn.Idle
    }

    /**
     * Runs [block] (a request to OpenAI), retrying while the phone can't reach it. Right after the
     * sign-in the browser is in front and this app is in the background, and some phones (Samsung's
     * battery saving, for one) cut a background app's internet. Trying again for a few minutes lets
     * it finish as soon as the person is back in the app.
     */
    private fun <T> whenReachable(mine: Int, block: () -> T): T {
        val deadline = System.currentTimeMillis() + NETWORK_RETRY_MS
        while (true) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (System.currentTimeMillis() > deadline || mine != attempt) throw e
                Thread.sleep(3_000)
            }
        }
    }

    private fun runSignIn(context: Context, mine: Int): SignIn {
        val registration = registration()
        val previous = session()
        val hostId = hostId()
        val state = random()
        val nonce = random()
        val verifier = random()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        val callbackReceiver = LoopbackReceiver.open(PREFERRED_PORT).also { receiver = it }
        val redirectUri = "http://127.0.0.1:${callbackReceiver.port}${LoopbackReceiver.CALLBACK_PATH}"
        val params = linkedMapOf(
            "client_id" to (registration?.clientId ?: "dynamic_agent_client"),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        )
        if (registration == null) {
            params["agent_name_hint"] = APP_NAME
        } else {
            registration.email?.let { params["login_hint"] = it }
            previous?.idToken?.let { params["id_token_hint"] = it }
            // Signed in before without allowing plan use: ask for that permission again.
            if (previous != null && PLAN_SCOPE !in previous.scopes) params["prompt"] = "consent"
        }
        val url = AUTHORIZE_URL + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val callback = callbackReceiver.use { it.awaitCallback(SIGN_IN_TIMEOUT_MS) { result ->
            if (result["state"] == state && result["code"] != null) {
                page("Signed in", "Go back to Finance Tracker to finish connecting ChatGPT.")
            } else {
                page("Not connected", "Nothing was changed. You can go back to Finance Tracker.")
            }
        } }
        if (receiver === callbackReceiver) receiver = null
        if (mine == attempt) _signIn.value = SignIn.Finishing
        if (callback["state"] != state) return SignIn.Failed("The sign-in answer didn't match. Please try again.")
        callback["error"]?.let { error ->
            return SignIn.Failed(
                if (error == "access_denied") "ChatGPT wasn't connected — the sign-in was cancelled." else "ChatGPT sign-in failed ($error)."
            )
        }
        val code = callback["code"] ?: return SignIn.Failed("ChatGPT didn't send a sign-in code. Please try again.")
        val returnedClient = callback["client_id"]
        val clientId = when {
            registration == null -> returnedClient ?: return SignIn.Failed("ChatGPT didn't finish registering the app. Please try again.")
            returnedClient != null && returnedClient != registration.clientId ->
                return SignIn.Failed("ChatGPT answered for a different registration. Please try again.")
            else -> registration.clientId
        }

        val tokens = whenReachable(mine) {
            postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirectUri,
                    "resource" to RESOURCE
                )
            )
        }.let { (status, body) ->
            if (status !in 200..299) {
                val reason = runCatching { JSONObject(body).optString("error_description").ifBlank { JSONObject(body).optString("error") } }
                    .getOrNull().orEmpty().ifBlank { body.take(120) }
                return SignIn.Failed("ChatGPT sign-in failed ($status${if (reason.isNotBlank()) ": $reason" else ""}). Please try again.")
            }
            JSONObject(body)
        }
        val idToken = tokens.optString("id_token").ifBlank { return SignIn.Failed("ChatGPT didn't confirm the account.") }
        val claims = IdTokenVerifier.verify(
            idToken, JSONObject(whenReachable(mine) { get(JWKS_URL) }), ISSUER, clientId, nonce, System.currentTimeMillis() / 1000
        )
        if (registration != null && registration.subject != claims.subject) {
            return SignIn.Failed(
                "That's a different ChatGPT account than the one connected before. Sign in with " +
                    (registration.email ?: "the same account") + "."
            )
        }
        val scopes = tokens.optString("scope").split(' ').filter { it.isNotBlank() }.toSet()
        saveRegistration(Registration(clientId, claims.subject, claims.email))
        saveSession(
            Session(
                accessToken = tokens.getString("access_token"),
                refreshToken = tokens.optString("refresh_token").ifBlank { null },
                idToken = idToken,
                expiresAt = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000,
                scopes = scopes
            )
        )
        if (PLAN_SCOPE !in scopes) {
            updateStatus(problem = "Signed in, but \"Use your ChatGPT plan\" wasn't allowed.")
            return SignIn.Failed(
                "You signed in, but didn't allow \"Use your ChatGPT plan\", so the app can't use ChatGPT. " +
                    "Tap Continue with ChatGPT to allow it."
            )
        }
        updateStatus(problem = null)
        val email = claims.email ?: "your ChatGPT account"
        val firstTime = prefs?.getBoolean(KEY_WELCOMED, false) != true
        prefs?.edit()?.putBoolean(KEY_WELCOMED, true)?.apply()
        return SignIn.Done(email, firstTime)
    }

    // ---- Tokens for requests ----

    /** A valid access token, renewed first if it's about to expire. Blocking; call off the main thread. */
    internal fun accessToken(forceRefresh: Boolean = false): String = synchronized(lock) {
        val session = session()?.takeIf { PLAN_SCOPE in it.scopes }
            ?: throw AiRequestException("ChatGPT isn't connected. Connect it in Settings > AI advisor.")
        if (!forceRefresh && System.currentTimeMillis() < session.expiresAt - 60_000) return session.accessToken
        val refreshToken = session.refreshToken ?: return expired()
        val clientId = registration()?.clientId ?: return expired()
        val (status, body) = try {
            postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refreshToken,
                    "resource" to RESOURCE
                )
            )
        } catch (e: Exception) {
            throw AiRequestException("Couldn't reach ChatGPT to renew the sign-in: ${e.message}", e)
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) {
            val code = json?.optString("error").orEmpty()
            // Only a definite "this sign-in is no longer valid" ends it; network trouble doesn't.
            if (status in 400..499 && code in UNUSABLE_REFRESH) return expired()
            throw AiRequestException("ChatGPT couldn't renew the sign-in right now ($status). Try again later.")
        }
        val tokens = json ?: throw AiRequestException("ChatGPT sent an unreadable sign-in renewal.")
        val renewed = Session(
            accessToken = tokens.getString("access_token"),
            refreshToken = tokens.optString("refresh_token").ifBlank { refreshToken },
            idToken = tokens.optString("id_token").ifBlank { session.idToken },
            expiresAt = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000,
            scopes = tokens.optString("scope").split(' ').filter { it.isNotBlank() }.toSet().ifEmpty { session.scopes }
        )
        saveSession(renewed)
        if (PLAN_SCOPE !in renewed.scopes) {
            updateStatus(problem = "Use of your ChatGPT plan is no longer allowed.")
            throw AiRequestException("Use of your ChatGPT plan is no longer allowed for this app. Connect again in Settings > AI advisor.")
        }
        renewed.accessToken
    }

    /** The sign-in can't be renewed (disconnected in ChatGPT, or too old): stop using it. */
    private fun expired(): Nothing {
        prefs?.edit()?.remove(KEY_SESSION)?.apply()
        updateStatus(problem = "The ChatGPT sign-in has ended. Tap Continue with ChatGPT to connect again.")
        throw AiRequestException("The ChatGPT sign-in has ended. Connect again in Settings > AI advisor.")
    }

    /** Signs out: ends the sign-in at OpenAI (best effort) and forgets the tokens on this phone.
     * The app's registration is kept, so connecting again later doesn't create a new one.
     * [onDone] gets false if OpenAI couldn't be told (then disconnect in ChatGPT's settings). */
    fun disconnect(onDone: (remoteConfirmed: Boolean) -> Unit = {}) {
        val session = session()
        val clientId = registration()?.clientId
        prefs?.edit()?.remove(KEY_SESSION)?.apply()
        updateStatus(problem = null)
        scope.launch {
            val refreshToken = session?.refreshToken
            val confirmed = if (refreshToken == null || clientId == null) {
                true
            } else {
                runCatching {
                    postForm(
                        REVOKE_URL,
                        mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId)
                    ).first in 200..299
                }.getOrDefault(false)
            }
            onDone(confirmed)
        }
    }

    // ---- Storage ----

    private fun updateStatus(problem: String?) {
        val session = session()
        val email = if (session != null && PLAN_SCOPE in session.scopes) {
            registration()?.email ?: "your ChatGPT account"
        } else {
            null
        }
        _status.value = Status(email, if (email == null) problem else null)
        AiSettings.refresh()
    }

    private fun hostId(): String {
        val p = prefs ?: return "urn:uuid:${UUID.randomUUID()}"
        p.getString(KEY_HOST_ID, null)?.let { return it }
        return "urn:uuid:${UUID.randomUUID()}".also { p.edit().putString(KEY_HOST_ID, it).commit() }
    }

    private fun registration(): Registration? = runCatching {
        val json = JSONObject(prefs?.getString(KEY_REGISTRATION, null) ?: return null)
        Registration(json.getString("client_id"), json.getString("sub"), json.optString("email").ifBlank { null })
    }.getOrNull()

    private fun saveRegistration(r: Registration) {
        val json = JSONObject().put("client_id", r.clientId).put("sub", r.subject).put("email", r.email ?: "")
        prefs?.edit()?.putString(KEY_REGISTRATION, json.toString())?.commit()
    }

    private fun session(): Session? = runCatching {
        val encrypted = prefs?.getString(KEY_SESSION, null) ?: return null
        val json = JSONObject(String(DeviceSecret.decrypt(encrypted) ?: return null, Charsets.UTF_8))
        val scopes = json.optJSONArray("scopes") ?: JSONArray()
        Session(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").ifBlank { null },
            idToken = json.optString("id_token").ifBlank { null },
            expiresAt = json.optLong("expires_at"),
            scopes = (0 until scopes.length()).map { scopes.getString(it) }.toSet()
        )
    }.getOrNull()

    private fun saveSession(s: Session) {
        val json = JSONObject()
            .put("access_token", s.accessToken)
            .put("refresh_token", s.refreshToken ?: "")
            .put("id_token", s.idToken ?: "")
            .put("expires_at", s.expiresAt)
            .put("scopes", JSONArray(s.scopes.toList()))
        prefs?.edit()?.putString(KEY_SESSION, DeviceSecret.encrypt(json.toString().toByteArray(Charsets.UTF_8)))?.commit()
    }

    // ---- HTTP ----

    private fun postForm(url: String, form: Map<String, String>): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) throw AiRequestException("Couldn't check ChatGPT's sign-in keys (${connection.responseCode}).")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private val UNUSABLE_REFRESH = setOf(
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused", "invalid_client"
    )

    private fun friendly(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "The ChatGPT sign-in took too long (${e.message ?: "timeout"}). Please try again."
        is IdTokenVerifier.InvalidIdTokenException -> e.message ?: "ChatGPT's sign-in answer was rejected."
        is AiRequestException -> e.message ?: "ChatGPT sign-in failed."
        is java.io.IOException ->
            "Couldn't reach ChatGPT to finish the sign-in (${e.javaClass.simpleName}: ${e.message ?: "no details"}). " +
                "Please try again, and come back to Finance Tracker right after signing in."
        else -> "ChatGPT sign-in failed: ${e.message}"
    }

    private val secureRandom = SecureRandom()
    private fun random(): String = b64(ByteArray(32).also(secureRandom::nextBytes))
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The page the browser shows after the sign-in, with a button back to the app. */
    private fun page(title: String, message: String): String {
        // A link the app declares it opens (see AndroidManifest.xml); Chrome sends other app links to Google Play.
        val back = "financetracker://chatgpt-done"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>body{font-family:sans-serif;margin:32px 20px;text-align:center;color:#222}
a{display:inline-block;margin-top:24px;padding:14px 22px;border-radius:24px;background:#111;color:#fff;text-decoration:none}</style>
</head><body><h2>$title</h2><p>$message</p><a href="$back">Back to Finance Tracker</a>
<p style="color:#777;font-size:14px;margin-top:24px">You can close this tab.</p></body></html>"""
    }
}
