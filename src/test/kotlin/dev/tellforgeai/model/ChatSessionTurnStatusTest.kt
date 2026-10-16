package dev.tellforgeai.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backward-compatibility checks for the persisted end-of-turn marker fields
 * (`lastTurnStatus`, `lastTurnPlanMode`): older tellforgeai.chats.xml files
 * without them must keep loading, and new values must round-trip.
 */
class ChatSessionTurnStatusTest {

    @Test
    fun `new chat has empty turn status defaults`() {
        val chat = ChatSession.new("hello")
        assertEquals("", chat.lastTurnStatus)
        assertFalse(chat.lastTurnPlanMode)
    }

    /** Strict=false never throws on unknown keys; decode without the new fields must fall back to defaults. */
    @Test
    fun `oldsession without the new fields deserializes with defaults`() {
        val oldJson = """{"id":"x","title":"t","messages":[]}"""
        val chat = Json { ignoreUnknownKeys = true }.decodeFromString(ChatSession.serializer(), oldJson)
        assertEquals("", chat.lastTurnStatus)
        assertFalse(chat.lastTurnPlanMode)
    }

    @Test
    fun `turn status round-trips through the persisted serializer`() {
        val chat = ChatSession.new("t").copy(lastTurnStatus = "complete", lastTurnPlanMode = true)
        val encoded = Json.encodeToString(ChatSession.serializer(), chat)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(ChatSession.serializer(), encoded)
        assertEquals("complete", decoded.lastTurnStatus)
        assertTrue(decoded.lastTurnPlanMode)
    }

    @Test
    fun `turn status fields are var for the XML serializer`() {
        for (name in listOf("lastTurnStatus", "lastTurnPlanMode")) {
            val prop = ChatSession::class.java.getDeclaredField(name)
            assertTrue("$name must not be final (val)", !java.lang.reflect.Modifier.isFinal(prop.modifiers))
        }
    }
}
