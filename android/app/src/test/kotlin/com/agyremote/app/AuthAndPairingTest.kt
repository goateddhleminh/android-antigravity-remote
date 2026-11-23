package com.agyremote.app

import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.QrPairingPayload
import com.agyremote.app.network.CompanionApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAndPairingTest {

    @Test
    fun `parses valid QR pairing JSON payload`() {
        val raw = """{"url":"https://my-win-pc.tailnet.ts.net","deviceName":"Gaming-PC","pairToken":"abc123token"}"""
        val result = QrPairingPayload.parse(raw)
        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("https://my-win-pc.tailnet.ts.net", payload.url)
        assertEquals("Gaming-PC", payload.deviceName)
        assertEquals("abc123token", payload.pairToken)
    }

    @Test
    fun `rejects invalid or incomplete QR payload`() {
        assertTrue(QrPairingPayload.parse("not-json").isFailure)
        assertTrue(QrPairingPayload.parse("""{"url":"","pairToken":"abc"}""").isFailure)
        assertTrue(QrPairingPayload.parse("""{"url":"https://pc.ts.net","pairToken":""}""").isFailure)
    }

    @Test
    fun `converts HTTPS base URL to WSS endpoint and normalizes host URLs`() {
        val host = PairedHost(
            id = "h1",
            name = "My PC",
            baseUrl = "https://pc.tailnet.ts.net/",
            deviceId = "d1",
            accessToken = "jwt",
            refreshToken = "ref",
            tokenExpiresAtMs = 100_000L,
        )
        assertEquals("wss://pc.tailnet.ts.net/ws", host.toWebSocketUrl())
        assertEquals("https://pc.tailnet.ts.net", CompanionApiClient.normalizeBaseUrl("pc.tailnet.ts.net/"))
        assertEquals("http://127.0.0.1:8443", CompanionApiClient.normalizeBaseUrl("http://127.0.0.1:8443/"))
    }

    @Test
    fun `detects expired JWT with safety skew`() {
        val host = PairedHost(
            id = "h1",
            name = "My PC",
            baseUrl = "https://pc.tailnet.ts.net",
            deviceId = "d1",
            accessToken = "jwt",
            refreshToken = "ref",
            tokenExpiresAtMs = 1_000_000L,
        )
        assertFalse(host.isAccessTokenExpired(nowMs = 900_000L, skewMs = 30_000L))
        assertTrue(host.isAccessTokenExpired(nowMs = 975_000L, skewMs = 30_000L))
        assertTrue(host.isAccessTokenExpired(nowMs = 1_050_000L, skewMs = 30_000L))
    }
}
