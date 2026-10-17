package com.nikosm.voiceassistant

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted persona transcript's cap — raised from 200 to 2000 messages on 2026-10-17
 * (`SettingsManager.MAX_PERSONA_MESSAGES`), because the phone-sized number was the only thing
 * evicting a desktop conversation.
 *
 * Two behaviours are pinned here because the raise touches both: the *newest* messages are the ones
 * kept, and a message that is dropped still has its cached audio and image files deleted. The second
 * half is the one a careless cap raise could lose — it lives in the same eviction loop.
 */
class PersonaHistoryCapTest {

    private val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)

    @Test
    fun `a long transcript keeps exactly the newest thousand exchanges`() {
        val messages = (0 until 2005).map {
            ChatMessage(role = if (it % 2 == 0) "user" else "assistant", text = "message $it")
        }

        manager.savePersonaMessages("Cap", messages)

        val kept = manager.getPersonaMessages("Cap")
        assertEquals(2000, kept?.size)
        assertEquals("message 5", kept?.first()?.text)
        assertEquals("message 2004", kept?.last()?.text)
    }

    @Test
    fun `the cached media of an evicted message is deleted, and a kept one is not`() {
        val root = TempRoots.newRoot("celeste-test")
        val doomed = root.resolve("doomed.wav").also { Files.writeString(it, "RIFF") }
        val kept = root.resolve("kept.wav").also { Files.writeString(it, "RIFF") }
        val messages = buildList {
            add(ChatMessage(role = "user", text = "old", audioFilePath = doomed.toString()))
            repeat(2000) { add(ChatMessage(role = "user", text = "message $it")) }
            add(ChatMessage(role = "assistant", text = "new", audioFilePath = kept.toString()))
        }

        manager.savePersonaMessages("Cap", messages)

        assertFalse("an evicted message's audio must not outlive it", Files.exists(doomed))
        assertTrue("a kept message's audio must survive", Files.exists(kept))
        assertEquals(2000, manager.getPersonaMessages("Cap")?.size)
    }
}
