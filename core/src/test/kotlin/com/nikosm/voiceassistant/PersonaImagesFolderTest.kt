package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Images live beside the chat they belong to (2026-10-18): `~/.celeste-linux/<persona>/images/`.
 *
 * Covers the three ways a picture arrives — picked artwork, a message attachment, a generated
 * image from the cache — and the one-time migration that gathers what an older install already
 * has. The backup test covers the last requirement: the exported file carries the bytes, so a
 * password-encrypted backup really contains everything.
 */
class PersonaImagesFolderTest {

    /** Writes a fixed payload to the target, like the real codec but with no JPEG work. */
    private class WritingCodec : ImageCodec {
        override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean =
            runCatching {
                Files.createDirectories(target.parent)
                Files.write(target, "jpeg:${source.fileName}".toByteArray())
                true
            }.getOrDefault(false)
    }

    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val manager = SettingsManager(env, store, WritingCodec())

    private fun persona(name: String, icon: String? = null, background: String? = null) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[Home] model",
        systemPrompt = "prompt",
        iconImageUri = icon,
        backgroundImageUri = background,
    )

    private fun personaDir(name: String): Path =
        env.homeDir.resolve(".celeste-linux").resolve(name)

    private fun tempFile(name: String, bytes: String): Path =
        Files.createTempFile(name, ".bin").also { Files.writeString(it, bytes) }

    @Test
    fun `picked artwork and attachments are written into the persona's images folder`() {
        val icon = manager.savePersonaImage(tempFile("icon", "icon-bytes"), "icon", "Sylvia")!!
        val attachment = manager.saveMessageImage(tempFile("photo", "photo-bytes"), "Sylvia")!!

        assertTrue("$icon", icon.startsWith(personaDir("Sylvia").resolve("images").toString()))
        assertTrue(attachment.startsWith(personaDir("Sylvia").resolve("images").toString()))
        assertTrue(Files.isRegularFile(Path.of(icon)))
        assertTrue(Files.isRegularFile(Path.of(attachment)))
    }

    @Test
    fun `a generated image moves out of the cache into the persona folder`() {
        val cacheFile = env.cacheDir.resolve("gen_1234.png")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "png-bytes")

        val adopted = manager.saveGeneratedImage("Sylvia", cacheFile)!!

        assertTrue(adopted.startsWith(personaDir("Sylvia").resolve("images").toString()))
        assertFalse("the cache must not keep the only copy", Files.exists(cacheFile))
        assertEquals("png-bytes", Files.readString(Path.of(adopted)))
    }

    @Test
    fun `migration gathers old artwork and message images and rewrites the chat`() {
        val oldArtwork = tempFile("old-icon", "old-icon")
        val oldMessageImage = tempFile("old-photo", "old-photo")
        manager.savePersonas(listOf(persona("Sylvia", icon = oldArtwork.toString())))
        manager.savePersonaMessages(
            "Sylvia",
            listOf(ChatMessage(role = "user", text = "look", imagePath = oldMessageImage.toString())),
        )
        val chatBefore = Files.readString(personaDir("Sylvia").resolve("chat.json"))
        assertTrue(chatBefore.contains(oldMessageImage.toString()))

        manager.migrateImagesToPersonaFolders()

        val migratedPersona = manager.getPersonas()!!.single()
        assertNotEquals(oldArtwork.toString(), migratedPersona.iconImageUri)
        assertTrue(migratedPersona.iconImageUri!!.startsWith(personaDir("Sylvia").resolve("images").toString()))
        assertTrue("the original pick is never moved", Files.isRegularFile(oldArtwork))

        val message = manager.getPersonaMessages("Sylvia")!!.single()
        assertTrue(message.imagePath!!.startsWith(personaDir("Sylvia").resolve("images").toString()))
        assertTrue(Files.isRegularFile(Path.of(message.imagePath!!)))
        assertTrue("the user's own file is copied, not moved", Files.isRegularFile(oldMessageImage))

        // Idempotent: a second pass must not create another copy.
        manager.migrateImagesToPersonaFolders()
        assertEquals(message.imagePath, manager.getPersonaMessages("Sylvia")!!.single().imagePath)
    }

    @Test
    fun `the backup carries message images and restores them into the new device's folder`() {
        manager.savePersonas(listOf(persona("Sylvia")))
        val image = manager.saveMessageImage(tempFile("photo", "photo-bytes"), "Sylvia")!!
        manager.savePersonaMessages(
            "Sylvia",
            listOf(ChatMessage(role = "user", text = "look", imagePath = image)),
        )

        val exported = manager.exportBackup()
        assertTrue("the backup must embed the image", exported.contains("messageImages"))
        assertTrue(exported.contains("image"))

        val otherEnv = FakeAppEnv()
        val restored = SettingsManager(otherEnv, InMemoryStore(), WritingCodec())
        assertTrue(restored.importBackup(exported))

        val message = restored.getPersonaMessages("Sylvia")!!.single()
        val restoredPath = Path.of(message.imagePath!!)
        assertTrue(
            "restored image must land in the new home's persona folder",
            restoredPath.startsWith(otherEnv.homeDir.resolve(".celeste-linux/Sylvia/images"))
        )
        assertArrayEquals(
            "the bytes are the exported picture",
            Files.readAllBytes(Path.of(image)),
            Files.readAllBytes(restoredPath),
        )
    }
}
