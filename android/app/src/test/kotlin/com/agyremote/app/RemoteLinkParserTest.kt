package com.agyremote.app

import com.agyremote.app.model.RemoteLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkParserTest {

    @Test
    fun `parses Antigravity Remote Control link and infers device name`() {
        val res = RemoteLinkParser.parse("https://antigravity.google.com/remote/admin-pc-rising-ember")
        assertTrue(res.isSuccess)
        val (name, url) = res.getOrThrow()
        assertEquals("admin-pc-rising-ember", name)
        assertEquals("https://antigravity.google.com/remote/admin-pc-rising-ember", url)
    }

    @Test
    fun `respects explicit custom name and normalizes missing https scheme`() {
        val res = RemoteLinkParser.parse("antigravity.google.com/c/123", customName = "My Windows PC")
        assertTrue(res.isSuccess)
        val (name, url) = res.getOrThrow()
        assertEquals("My Windows PC", name)
        assertEquals("https://antigravity.google.com/c/123", url)
    }

    @Test
    fun `rejects non-https and unsafe schemes`() {
        assertTrue(RemoteLinkParser.parse("http://antigravity.google.com").isFailure)
        assertTrue(RemoteLinkParser.parse("javascript:alert(1)").isFailure)
        assertTrue(RemoteLinkParser.parse("").isFailure)
    }
}
