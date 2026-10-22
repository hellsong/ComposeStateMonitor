package io.github.hellsong.composemonitor.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeMonitorInspectorTest {

    private data class Item(val id: Int)

    @Test
    fun firstCompositionIsActivation() {
        assertEquals("activated on `Fn`", describeRecomposition("Fn", null, mapOf("a" to 1)))
    }

    @Test
    fun reportsChangedValues() {
        val message = describeRecomposition("Fn", mapOf("a" to 1, "b" to "x"), mapOf("a" to 2, "b" to "x"))
        assertTrue(message.startsWith("Fn recomposed because"), message)
        assertTrue("`a` changed from `1` to `2`" in message, message)
        assertFalse("`b`" in message, message)
    }

    @Test
    fun reportsNewInstanceOfEqualObject() {
        val message = describeRecomposition("Fn", mapOf("item" to Item(1)), mapOf("item" to Item(1)))
        assertTrue("`item` instance changed, but content remains the same" in message, message)
    }

    @Test
    fun ignoresNewInstancesOfValueTypes() {
        // Each `to` boxes the number separately, and buildString creates a new String instance.
        val old = mapOf<String, Any?>("int" to 1000, "long" to 5L, "text" to buildString { append("ab") })
        val new = mapOf<String, Any?>("int" to 1000, "long" to 5L, "text" to buildString { append("ab") })
        val message = describeRecomposition("Fn", old, new)
        assertTrue(message.startsWith("Fn recomposed, but reason is unknown"), message)
    }

    @Test
    fun reportsAddedKeys() {
        val message = describeRecomposition("Fn", mapOf("a" to 1), mapOf("a" to 1, "b" to null))
        assertTrue("`b` added: `null`" in message, message)
    }
}
