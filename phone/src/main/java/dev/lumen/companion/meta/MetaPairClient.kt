/*
 * Ported from kinesis (https://github.com/callbacked/kinesis), Copyright (c) 2026 callbacked,
 * MIT License (LICENSE-kinesis): Sources/Kinesis/MetaPairClient.swift. Modified for Rokid Lumen.
 */
package dev.lumen.companion.meta

import java.util.Base64
import okhttp3.Request
import org.json.JSONObject

/**
 * The band's ownership exchanges with Meta's hardware graph (form posts). Receipts travel
 * verbatim. Blocking: off the main thread.
 */
class MetaPairClient(private val session: MetaSession) {
    data class Pending(val signature: ByteArray, val receipt: String)
    data class Final(val signature: ByteArray, val receipt: String, val devicePublicKey: ByteArray?)

    /** The ceremony's `pair_request` event (bytes in hex) to Meta: the pending receipt. */
    fun pairRequest(request: JSONObject): Pending {
        val fields = pairRequestFields(
            deviceCert = hex(request.getString("device_cert")),
            serial = request.getString("serial"),
            secondaryCert = hex(request.getString("secondary_cert")),
            nonce = hex(request.getString("nonce")),
            appPublicKey = hex(request.getString("app_pubkey")),
        )
        return parsePending(execute("$HOST/pair_request", fields))
    }

    /** The band's receipt (the ceremony's `pair` event) to Meta: the final receipt. */
    fun pair(pair: JSONObject): Final {
        val fields = pairFields(pair.getString("receipt"), hex(pair.getString("signature")))
        return parseFinal(execute("$HOST/pair", fields))
    }

    private fun base() = listOf(
        "access_token" to MetaAuth.HW_CLIENT,
        "user_access_token" to session.accessToken,
        "user_token_universe" to UNIVERSE,
        "pair_protocol_version" to "3",
    )

    internal fun pairFields(receipt: String, signature: ByteArray) =
        base() + listOf(
            "device_pending_ownership_receipt" to receipt,
            "device_pending_ownership_receipt_signature" to b64(signature),
        )

    internal fun pairRequestFields(deviceCert: ByteArray, serial: String, secondaryCert: ByteArray, nonce: ByteArray, appPublicKey: ByteArray) =
        base() + listOf(
            "device_cert" to b64(deviceCert),
            "serial_number" to serial,
            "additional_data" to additionalData(nonce, appPublicKey, b64(secondaryCert)),
        )

    private fun execute(url: String, fields: List<Pair<String, String>>): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").post(MetaAuth.form(fields)).build()
        MetaAuth.http.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            val error = json?.optJSONObject("error")
            if (isSessionFailure(response.code, error)) {
                throw MetaException("Your Meta session expired. Sign in again to claim the band.", sessionExpired = true)
            }
            if (response.code != 200 || json == null) throw MetaException("The band claim service failed (HTTP ${response.code}). Try again.")
            if (error != null) throw MetaException("The band claim service refused the request (code ${error.optInt("code", -1)}). Try again.")
            return json
        }
    }

    companion object {
        const val HOST = "https://graph.facebook-hardware.com"
        const val UNIVERSE = "ar"

        /** The session can't work any more: an auth status, or the graph's expired-token code (190). */
        fun isSessionFailure(status: Int, error: JSONObject?) = status == 401 || status == 403 || error?.optInt("code") == 190

        fun additionalData(nonce: ByteArray, appPublicKey: ByteArray, secondaryCert: String) =
            "{\"device_nonce\":\"${b64(nonce)}\",\"app_pubkey\":\"${b64(appPublicKey)}\",\"secondary_cert\":\"$secondaryCert\"}"

        fun parsePending(json: JSONObject): Pending {
            val receipt = json.optString("pending_ownership_receipt")
            val signature = unb64(json.optString("receipt_signature"))
            if (receipt.isEmpty() || signature == null) throw MetaException("The band claim service didn't return a receipt. Try again.")
            return Pending(signature, receipt)
        }

        fun parseFinal(json: JSONObject): Final {
            val receipt = json.optString("final_ownership_receipt")
            val signature = unb64(json.optString("receipt_signature"))
            if (receipt.isEmpty() || signature == null) throw MetaException("The band claim service didn't return a final receipt. Try again.")
            // The live server often omits the band's key; the reference app goes on without it.
            val key = deviceKey(json) ?: runCatching { deviceKey(JSONObject(receipt)) }.getOrNull()
            return Final(signature, receipt, key)
        }

        private fun deviceKey(json: JSONObject): ByteArray? =
            json.optJSONObject("additional_data")?.optString("device_ec_public_key")?.let(::unb64)

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

        private fun unb64(text: String): ByteArray? =
            text.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

        private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
