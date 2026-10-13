package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the device bridge that decide what the agent gets: the token, the wrapper it calls and the
 * one-line hint in the prompt. The Android answers themselves are covered by DeviceQueryServerTest.
 */
class AgentDeviceBridgeTest {

    @Test
    fun `a token is 32 hex characters and unique per run`() {
        val first = AgentDeviceBridge.newToken()
        val second = AgentDeviceBridge.newToken()
        assertEquals(32, first.length)
        assertTrue("token must be hex, was $first", first.all { it in "0123456789abcdef" })
        assertFalse("two runs must not share a token", first == second)
    }

    @Test
    fun `the wrapper points at the loopback port and reads the token from its file`() {
        val script = AgentDeviceBridge.wrapperScript(port = 43210)
        assertTrue(script.contains("PORT = 43210"))
        assertTrue(script.contains("http://127.0.0.1:"))
        // The token file is built from path segments, not one literal.
        assertTrue(script.contains("\"mh-device\""))
        assertTrue(script.contains("\"token\""))
        assertTrue(script.contains("X-MH-Token"))
        assertTrue("must use the standard library, not curl", script.contains("urllib.request"))
        assertFalse("a token must never be baked into the script", script.contains("0123456789abcdef0123456789abcdef"))
        assertTrue("an unreachable bridge must be reported, not hidden", script.contains("bridge_unreachable"))
    }

    @Test
    fun `the wrapper documents exactly the two read-only commands`() {
        val script = AgentDeviceBridge.wrapperScript(port = 1)
        assertTrue(script.contains("mh-device info"))
        assertTrue(script.contains("mh-device apps"))
        assertTrue(script.contains("--offset"))
        assertTrue(script.contains("--limit"))
        assertTrue("the read-only promise belongs in the help text", script.contains("Read-only by design"))
        assertFalse("no write or launch command may be documented", script.contains("install_app"))
        assertFalse(script.contains("uninstall"))
        assertFalse(script.contains("launch_app"))
    }

    @Test
    fun `the prompt hint names the command and its limit`() {
        val note = AgentDeviceBridge.promptNote()
        assertTrue(note.contains("mh-device info"))
        assertTrue(note.contains("mh-device apps"))
        assertTrue(note.contains("cannot install"))
    }
}
