package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP screen's text-to-config parsing, tested away from Compose.
 *
 * These are the places a typo becomes a different server: arguments are one per line, env and
 * headers split at the **first** separator (a URL inside a header contains colons; a token may
 * contain `=`), and a new server's id must be stable and unique.
 */
class McpScreenHelpersTest {

    @Test
    fun `arguments are one per line, blanks dropped`() {
        assertEquals(
            listOf("--root", "/home/user/notes", "--read-only"),
            parseMcpArgs("\n--root\n  /home/user/notes  \n\n--read-only\n"),
        )
    }

    @Test
    fun `environment splits at the first equals, so a value may contain more`() {
        assertEquals(
            mapOf("TOKEN" to "a=b=c", "MODE" to "read"),
            parseMcpKeyValues("TOKEN=a=b=c\nMODE=read", '='),
        )
    }

    @Test
    fun `headers split at the first colon, so a value may contain a url`() {
        assertEquals(
            mapOf("Authorization" to "Bearer abc:def", "X-Host" to "http://127.0.0.1:9000"),
            parseMcpKeyValues(
                "Authorization: Bearer abc:def\nX-Host: http://127.0.0.1:9000",
                ':',
            ),
        )
    }

    @Test
    fun `a line without a separator or with an empty key is ignored`() {
        assertEquals(
            mapOf("OK" to "1"),
            parseMcpKeyValues("no separator here\n=missing\n:also missing\nOK=1", '='),
        )
    }

    @Test
    fun `ids are slugged from the name and made unique`() {
        assertEquals("my_server", uniqueMcpServerId("My Server!", emptySet()))
        assertEquals("my_server-2", uniqueMcpServerId("My Server!", setOf("my_server")))
        assertEquals("my_server-3", uniqueMcpServerId("My Server!", setOf("my_server", "my_server-2")))
        assertTrue(uniqueMcpServerId("!!!", emptySet()).isNotBlank())
    }
}
