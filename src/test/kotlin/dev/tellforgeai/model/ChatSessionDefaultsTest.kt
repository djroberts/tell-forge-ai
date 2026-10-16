package dev.tellforgeai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization-compatibility checks for the ChatSession fields added for
 * per-chat provider memory (`providerProfileId`, `providerName`, `mode`):
 * defaults must keep older tellforgeai.chats.xml files loading unchanged.
 */
class ChatSessionDefaultsTest {

    @Test
    fun `new chat has empty provider and mode defaults`() {
        val chat = ChatSession.new("hello")
        assertEquals("", chat.providerProfileId)
        assertEquals("", chat.providerName)
        assertEquals("", chat.mode)
    }

    @Test
    fun `autoTitle unchanged by the new fields`() {
        // No messages yet: falls back to the creation title.
        val chat = ChatSession.new("hello world")
        assertEquals("hello world", chat.autoTitle())
        chat.messages.add(dev.tellforgeai.llm.ChatMessage.user("Read the file now"))
        assertEquals("Read the file now", chat.autoTitle())
    }

    @Test
    fun `mode fields are var for the XML serializer`() {
        // Reflection check guarding the persisted-`var` convention.
        for (name in listOf("providerProfileId", "providerName", "mode")) {
            val prop = ChatSession::class.java.getDeclaredField(name)
            assertTrue("$name must not be final (val)", !java.lang.reflect.Modifier.isFinal(prop.modifiers))
        }
    }

    @Test
    fun `fields accept writes`() {
        val chat = ChatSession.new("t")
        chat.providerProfileId = "p1"
        chat.providerName = "OpenRouter"
        chat.mode = "act"
        assertEquals("p1", chat.providerProfileId)
        assertEquals("OpenRouter", chat.providerName)
        assertEquals("act", chat.mode)
        assertFalse(chat.mixedModelsUsage)
    }
}
