/*
 * Ported from kinesis (https://github.com/callbacked/kinesis), Copyright (c) 2026 callbacked,
 * MIT License (LICENSE-kinesis): Sources/Kinesis/MetaAuth.swift. Modified for Rokid Lumen.
 */
package dev.lumen.companion.meta

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The Meta account session the hardware graph needs (universe "ar"). Never logged, never stored. */
data class MetaSession(val accessToken: String, val userId: String, val deviceId: String)

class MetaException(message: String, val sessionExpired: Boolean = false) : Exception(message)

/**
 * Signing in to Meta as kinesis does: an anonymous tokens query, the sign-in on Meta's own page
 * (a WebView: email, password and two-factor stay there), the blob decrypt, and the ar-genai
 * exchange that gives the account session. Blocking calls: off the main thread.
 *
 * The client ids are Meta's own, the same in every copy of Meta's apps: they name the app that
 * asks, not a person, and aren't secrets. The routes are undocumented and can change.
 */
object MetaAuth {
    const val FRL_CLIENT = "FRL|388177446008673|083800dd7efbbd42eab18c9886d79c18"
    const val AR_CLIENT = "AR|306760944872162|a919421a55a8ea18080ab2f10f57f1be"
    const val HW_CLIENT = "HW|1312539125771114|98588f106d5d542adbf590619ca071fe"
    private const val TOKENS_QUERY = "https://meta.graph.meta.com/webview_tokens_query"
    private const val BLOBS_DECRYPT = "https://meta.graph.meta.com/webview_blobs_decrypt"
    private const val LOGIN = "https://ar-genai.graph.meta.com/login"
    private const val AUTH_ENTRY = "https://auth.meta.com/?native_app_id=388177446008673&source_app_id=388177446008673&native_sso_etoken="

    data class SsoTokens(val nativeSsoToken: String, val etoken: String) {
        val authEntryUrl: String get() = AUTH_ENTRY + etoken
    }

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    }

    /** Step 1: the anonymous tokens query (multipart, the FRL client). */
    fun tokensQuery(): SsoTokens {
        val lsd = makeLsd()
        val json = postMultipart(TOKENS_QUERY, listOf("access_token" to FRL_CLIENT, "lsd" to lsd, "jazoest" to jazoest(lsd)))
        val native = json.optString("native_sso_token")
        val etoken = json.optString("native_sso_etoken")
        if (native.isEmpty() || etoken.isEmpty()) throw MetaException("Meta didn't return sign-in tokens. Try again.")
        return SsoTokens(native, etoken)
    }

    /** The token the sign-in callback must carry: the first 16 hex digits of SHA-256(native_sso_token). */
    fun expectedCallbackToken(nativeSsoToken: String): String =
        MessageDigest.getInstance("SHA-256").digest(nativeSsoToken.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun callbackMatches(token: String?, nativeSsoToken: String): Boolean =
        token != null && token.length == 16 && MessageDigest.isEqual(token.toByteArray(), expectedCallbackToken(nativeSsoToken).toByteArray())

    /** Step 3: the sign-in's blob into the FRL access token. */
    fun decryptBlob(blob: String, requestToken: String): String {
        val lsd = makeLsd()
        val json = postMultipart(
            BLOBS_DECRYPT,
            listOf("blob" to blob, "request_token" to requestToken, "access_token" to FRL_CLIENT, "lsd" to lsd, "jazoest" to jazoest(lsd)),
        )
        return json.optString("access_token").ifEmpty { throw MetaException("Meta didn't confirm the sign-in. Try again.") }
    }

    /** Step 4: the FRL token for the account session (universe "ar"). */
    fun login(frlAccessToken: String): MetaSession {
        val deviceId = UUID.randomUUID().toString().uppercase()
        val form = form(
            listOf(
                "frl_access_token" to frlAccessToken,
                "logging_session_id" to UUID.randomUUID().toString().uppercase(),
                "format" to "json",
                "device_id" to deviceId,
                "generate_session_cookies" to "1",
                "generate_analytics_claim" to "1",
                "method" to "POST",
            ),
        )
        val json = post(Request.Builder().url(LOGIN).header("Authorization", "OAuth $AR_CLIENT").post(form))
        val token = json.optString("access_token")
        val user = json.opt("user_id")?.toString().orEmpty()
        if (token.isEmpty() || user.isEmpty()) throw MetaException("Meta didn't return an account session. Try again.")
        return MetaSession(token, user, deviceId)
    }

    fun makeLsd(): String = "S0." + (1..6).map { (0..9).random() }.joinToString("")

    fun jazoest(lsd: String): String = "2" + lsd.sumOf { it.code }

    private fun postMultipart(url: String, fields: List<Pair<String, String>>): JSONObject {
        val body = MultipartBody.Builder("lumen.form." + UUID.randomUUID()).setType(MultipartBody.FORM)
            .apply { fields.forEach { (name, value) -> addFormDataPart(name, value) } }
            .build()
        return post(Request.Builder().url(url).header("Origin", "https://auth.meta.com").post(body))
    }

    private fun post(request: Request.Builder): JSONObject {
        http.newCall(request.header("Accept", "application/json").build()).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (response.code != 200 || json == null) throw MetaException("Meta sign-in failed (HTTP ${response.code}). Try again.")
            json.optJSONObject("error")?.let {
                throw MetaException("Meta sign-in failed (code ${it.optInt("code", -1)}, subcode ${it.optInt("error_subcode", -1)}). Try again.")
            }
            return json
        }
    }

    /** A form body escaped as kinesis does it: everything but RFC 3986's unreserved characters. */
    internal fun form(fields: List<Pair<String, String>>): RequestBody =
        urlForm(fields).toRequestBody("application/x-www-form-urlencoded".toMediaType())

    fun urlForm(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (name, value) -> "$name=${formEscape(value)}" }

    private fun formEscape(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar()) else append("%%%02X".format(c))
        }
    }
}
