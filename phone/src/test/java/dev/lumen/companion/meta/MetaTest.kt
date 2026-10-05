package dev.lumen.companion.meta

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kinesis reference tests (Tests/KinesisTests/MetaPairClientTests.swift), ported. */
class MetaTest {
    private val session = MetaSession("test-access-token", "1234567890", "device")
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun pairRequestEncodesTheExactReferenceFieldSet() {
        val nonce = ByteArray(16) { it.toByte() }
        val key = ByteArray(64) { 9 }
        val fields = MetaPairClient(session).pairRequestFields(byteArrayOf(1, 2, 3, 4), "TESTSERIAL0001", byteArrayOf(5, 6, 7, 8), nonce, key)
        assertEquals(
            listOf("access_token", "user_access_token", "user_token_universe", "pair_protocol_version", "device_cert", "serial_number", "additional_data"),
            fields.map { it.first },
        )
        assertEquals("HW|1312539125771114|98588f106d5d542adbf590619ca071fe", fields[0].second)
        assertEquals("test-access-token", fields[1].second)
        assertEquals("ar", fields[2].second)
        assertEquals("3", fields[3].second)
        assertEquals(b64(byteArrayOf(1, 2, 3, 4)), fields[4].second)
        assertEquals("TESTSERIAL0001", fields[5].second)
        assertEquals(
            "{\"device_nonce\":\"${b64(nonce)}\",\"app_pubkey\":\"${b64(key)}\",\"secondary_cert\":\"${b64(byteArrayOf(5, 6, 7, 8))}\"}",
            fields[6].second,
        )
    }

    @Test
    fun pairEncodesTheExactReferenceFieldSet() {
        val receipt = """{"receipt_type":"DevicePendingOwnershipReceipt"}"""
        val fields = MetaPairClient(session).pairFields(receipt, byteArrayOf(10, 11, 12))
        assertEquals(receipt, fields[4].second)
        assertEquals(b64(byteArrayOf(10, 11, 12)), fields[5].second)
        assertEquals("device_pending_ownership_receipt_signature", fields[5].first)
    }

    @Test
    fun urlFormEscapesReceiptCharactersForATransportSafeBody() {
        assertEquals("a=x%2By%3Dz%201&b=%7B%22q%22%3A%22%5C%2F%22%7D", MetaAuth.urlForm(listOf("a" to "x+y=z 1", "b" to """{"q":"\/"}""")))
    }

    @Test
    fun lsdFollowsTheReferenceShape() {
        val lsd = MetaAuth.makeLsd()
        assertEquals(9, lsd.length)
        assertTrue(lsd.startsWith("S0.") && lsd.drop(3).all { it.isDigit() })
        assertEquals("2" + lsd.sumOf { it.code }, MetaAuth.jazoest(lsd))
    }

    @Test
    fun pendingAndFinalReceiptsParseWithTheBandKey() {
        val pending = MetaPairClient.parsePending(
            JSONObject().put("pending_ownership_receipt", """{"receipt_type":"ServerPendingOwnershipReceipt"}""").put("receipt_signature", b64(byteArrayOf(1, 2, 3))),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), pending.signature)
        val point = ByteArray(64) { it.toByte() }
        val final = MetaPairClient.parseFinal(
            JSONObject().put("final_ownership_receipt", "{}").put("receipt_signature", b64(byteArrayOf(4, 5)))
                .put("additional_data", JSONObject().put("device_ec_public_key", b64(point))),
        )
        assertArrayEquals(point, final.devicePublicKey)
        val embedded = MetaPairClient.parseFinal(
            JSONObject().put("final_ownership_receipt", "{\"additional_data\":{\"device_ec_public_key\":\"${b64(point)}\"}}").put("receipt_signature", b64(byteArrayOf(4, 5))),
        )
        assertArrayEquals(point, embedded.devicePublicKey)
        assertNull(MetaPairClient.parseFinal(JSONObject().put("final_ownership_receipt", "{}").put("receipt_signature", "AAAA")).devicePublicKey)
        assertTrue(runCatching { MetaPairClient.parsePending(JSONObject().put("receipt_signature", "AAAA")) }.isFailure)
    }

    @Test
    fun callbackValidationComparesTheTruncatedTokenDigest() {
        val native = "epfEORXEWkN2obB5"
        val expected = MetaAuth.expectedCallbackToken(native)
        assertEquals(16, expected.length)
        assertTrue(MetaAuth.callbackMatches(expected, native))
        assertFalse(MetaAuth.callbackMatches(expected.dropLast(1) + if (expected.endsWith("0")) "1" else "0", native))
        assertFalse(MetaAuth.callbackMatches(null, native))
        assertFalse(MetaAuth.callbackMatches("short", native))
    }

    @Test
    fun sessionFailuresCoverAuthStatusesAndExpiredTokenErrors() {
        assertTrue(MetaPairClient.isSessionFailure(401, null))
        assertTrue(MetaPairClient.isSessionFailure(403, null))
        assertTrue(MetaPairClient.isSessionFailure(400, JSONObject().put("code", 190)))
        assertFalse(MetaPairClient.isSessionFailure(400, JSONObject().put("code", 1)))
        assertFalse(MetaPairClient.isSessionFailure(500, null))
    }
}
