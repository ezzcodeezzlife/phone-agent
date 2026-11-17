package dev.phoneagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

/**
 * "Sign in with ChatGPT", open-source / locally hosted flow: the app registers itself as an OAuth
 * client on first sign-in (client_id=dynamic_agent_client) and gets permission to run Responses API
 * requests on the user's ChatGPT plan. No API key, no client secret.
 * https://developers.openai.com/siwc/token-sharing-open-source
 *
 * Redirect: an HTTP loopback listener on 127.0.0.1 inside the app (Chrome on the same phone
 * reaches it). For emulator tests the browser can also run on the PC with `adb forward tcp:1455 tcp:1455`.
 */
object Auth {
    const val APP_NAME = "Phone Agent"
    const val ISSUER = "https://auth.openai.com"
    private const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    private const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    private const val REVOKE = "$ISSUER/api/accounts/oauth/revoke"
    private const val JWKS = "$ISSUER/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    private lateinit var prefs: SharedPreferences
    @Volatile private var server: ServerSocket? = null

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    /** Stable, opaque host ID for this installation (ext_agent_host_id). */
    val hostId: String
        get() {
            prefs.getString("host_id", null)?.let { return it }
            val id = "urn:uuid:" + UUID.randomUUID()
            prefs.edit().putString("host_id", id).apply()
            return id
        }

    val email: String? get() = prefs.getString("email", null)
    val clientId: String? get() = prefs.getString("client_id", null)
    val signedIn: Boolean get() = prefs.getString("refresh_token", null) != null
    val planEnabled: Boolean get() = (prefs.getString("scopes", "") ?: "").split(" ").contains(PLAN_SCOPE)

    /**
     * Starts the loopback listener, hands the authorize URL to [onUrl] (caller opens a browser) and
     * reports completion via [onDone] (null = success, else an error message). Runs on a background thread.
     */
    fun startLogin(onUrl: (String) -> Unit, onDone: (String?) -> Unit) {
        Thread {
            try {
                server?.close()
                val loopback = InetAddress.getByName("127.0.0.1")
                val ss = try { ServerSocket(1455, 8, loopback) } catch (e: Exception) { ServerSocket(0, 8, loopback) }
                server = ss
                ss.soTimeout = 10 * 60_000
                val redirect = "http://127.0.0.1:${ss.localPort}/auth/callback"
                val state = random(32)
                val nonce = random(32)
                val verifier = random(32)
                val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                val saved = clientId

                val p = LinkedHashMap<String, String>()
                p["response_type"] = "code"
                p["redirect_uri"] = redirect
                p["scope"] = SCOPES
                p["resource"] = RESOURCE
                p["state"] = state
                p["nonce"] = nonce
                p["code_challenge_method"] = "S256"
                p["code_challenge"] = challenge
                p["ext_agent_host_id"] = hostId
                if (saved == null) {
                    // First sign-in registers this installation as its own client.
                    p["client_id"] = "dynamic_agent_client"
                    p["agent_name_hint"] = APP_NAME
                } else {
                    p["client_id"] = saved
                    email?.let { p["login_hint"] = it }
                }
                onUrl(AUTHORIZE + "?" + p.entries.joinToString("&") { it.key + "=" + Net.encQuery(it.value) })

                // Serve until the callback arrives. Each connection gets its own thread: browsers open
                // idle pre-connections and ask for /favicon.ico, neither may block or abort the sign-in.
                val got = java.util.concurrent.LinkedBlockingQueue<Map<String, String>>()
                Thread {
                    while (!ss.isClosed) {
                        val s = try { ss.accept() } catch (_: Exception) { break }
                        Thread {
                            try {
                                s.soTimeout = 30_000
                                val requestLine = s.getInputStream().bufferedReader().readLine() ?: ""
                                val path = requestLine.split(" ").getOrNull(1) ?: ""
                                if (!path.startsWith("/auth/callback")) {
                                    respond(s, 404, "Not found")
                                } else {
                                    val q = parseQuery(path.substringAfter('?', ""))
                                    respond(s, 200, page(q["state"] == state && q["error"] == null && q["code"] != null, q["error"]))
                                    got.offer(q)
                                }
                            } catch (_: Exception) {
                                try { s.close() } catch (_: Exception) {}
                            }
                        }.start()
                    }
                }.start()
                val query = got.poll(10, java.util.concurrent.TimeUnit.MINUTES)
                ss.close()
                server = null
                if (query == null) throw IllegalStateException("Timed out: no response from the browser")

                if (query["state"] != state) throw IllegalStateException("state does not match")
                query["error"]?.let { throw IllegalStateException("OAuth: $it ${query["error_description"] ?: ""}".trim()) }
                val issued = if (saved == null) {
                    query["client_id"] ?: throw IllegalStateException("Registration incomplete (no client_id in the callback)")
                } else {
                    if (query["client_id"] != null && query["client_id"] != saved) throw IllegalStateException("Callback client_id does not match")
                    saved
                }
                // Keep the issued client ID even if the rest fails, so a retry reuses the registration.
                prefs.edit().putString("client_id", issued).apply()

                val tok = Net.postForm(TOKEN, mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to issued,
                    "code" to query["code"]!!,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect,
                    "resource" to RESOURCE,
                ))
                if (tok.code != 200) {
                    if (tok.body.contains("invalid_grant")) {
                        throw IllegalStateException("OpenAI rejected the sign-in (invalid_grant). Usually this means a ChatGPT account without Plus/Pro.")
                    }
                    throw IllegalStateException("Token-Austausch ${tok.code}: ${tok.body.take(300)}")
                }
                val j = JSONObject(tok.body)
                val claims = verifyIdToken(j.getString("id_token"), issued, nonce)
                val scopes = j.optString("scope")
                prefs.edit()
                    .putString("sub", claims.optString("sub"))
                    .putString("email", claims.optString("email"))
                    .putString("scopes", scopes)
                    .apply()
                if (!scopes.split(" ").contains(PLAN_SCOPE)) {
                    throw IllegalStateException("ChatGPT plan usage was not allowed. Please sign in again and approve it.")
                }
                saveTokens(j)
                onDone(null)
            } catch (e: Exception) {
                try { server?.close() } catch (_: Exception) {}
                server = null
                onDone(e.message ?: e.toString())
            }
        }.start()
    }

    /** Current access token, refreshed shortly before it expires (refresh tokens rotate). */
    @Synchronized
    fun accessToken(): String {
        val at = prefs.getString("access_token", null) ?: throw IllegalStateException("Not signed in")
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 120_000) return at
        val rt = prefs.getString("refresh_token", null) ?: throw IllegalStateException("Not signed in")
        val r = Net.postForm(TOKEN, mapOf(
            "grant_type" to "refresh_token",
            "client_id" to (clientId ?: throw IllegalStateException("No client_id")),
            "refresh_token" to rt,
            "resource" to RESOURCE,
        ))
        if (r.code != 200) {
            if (r.code in 400..499) clearTokens()   // rejected refresh token is gone for good
            throw IllegalStateException("Token-Refresh ${r.code}: ${r.body.take(200)}")
        }
        val j = JSONObject(r.body)
        saveTokens(j)
        return j.getString("access_token")
    }

    fun logout(onDone: () -> Unit) {
        Thread {
            val rt = prefs.getString("refresh_token", null)
            val cid = clientId
            if (rt != null && cid != null) {
                try { Net.postForm(REVOKE, mapOf("token" to rt, "token_type_hint" to "refresh_token", "client_id" to cid)) } catch (_: Exception) {}
            }
            clearTokens()
            onDone()
        }.start()
    }

    private fun saveTokens(j: JSONObject) {
        val e = prefs.edit()
            .putString("access_token", j.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        if (j.has("refresh_token")) e.putString("refresh_token", j.getString("refresh_token"))
        if (j.has("id_token")) e.putString("id_token", j.getString("id_token"))
        if (j.has("scope")) e.putString("scopes", j.getString("scope"))
        e.commit()
    }

    private fun clearTokens() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("id_token").remove("expires_at").commit()
    }

    /** RS256 signature check against OpenAI's JWKS plus iss/aud/exp/nonce. */
    private fun verifyIdToken(idToken: String, clientId: String, nonce: String): JSONObject {
        val parts = idToken.split(".")
        require(parts.size == 3) { "Invalid ID token" }
        val header = JSONObject(String(b64urlDecode(parts[0])))
        val payload = JSONObject(String(b64urlDecode(parts[1])))
        require(header.optString("alg") == "RS256") { "Unerwarteter Token-Algorithmus ${header.optString("alg")}" }
        val jwks = Net.get(JWKS)
        require(jwks.code == 200) { "JWKS ${jwks.code}" }
        val keys = JSONObject(jwks.body).getJSONArray("keys")
        var key: JSONObject? = null
        for (i in 0 until keys.length()) {
            val k = keys.getJSONObject(i)
            if (k.optString("kid") == header.optString("kid")) key = k
        }
        requireNotNull(key) { "Signing key not found" }
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64urlDecode(key.getString("n"))), BigInteger(1, b64urlDecode(key.getString("e"))))
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(pub)
        sig.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(sig.verify(b64urlDecode(parts[2]))) { "Invalid ID token signature" }
        require(payload.optString("iss") == ISSUER) { "ID-Token: falscher Issuer" }
        val aud = payload.opt("aud")
        val audOk = when (aud) {
            is String -> aud == clientId
            is org.json.JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        require(audOk) { "ID-Token: falsche Audience" }
        require(payload.optLong("exp") * 1000 > System.currentTimeMillis() - 5_000) { "ID token expired" }
        require(payload.optString("nonce") == nonce) { "ID token does not belong to this sign-in" }
        return payload
    }

    private fun respond(s: Socket, code: Int, html: String) {
        val bytes = html.toByteArray()
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        try {
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        } finally {
            s.close()
        }
    }

    private fun page(ok: Boolean, error: String?): String {
        val msg = if (ok) "Phone Agent is connected to ChatGPT." else "Sign-in failed${error?.let { ": $it" } ?: ""}."
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Phone Agent</title></head><body style="font-family:sans-serif;padding:32px;text-align:center">
            <h2>$msg</h2><p><a href="phoneagent://auth-done" style="font-size:18px">Back to the app</a></p></body></html>"""
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split("&").filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore("=")
            val v = it.substringAfter("=", "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun random(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let(::b64url)
    private fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun b64urlDecode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
