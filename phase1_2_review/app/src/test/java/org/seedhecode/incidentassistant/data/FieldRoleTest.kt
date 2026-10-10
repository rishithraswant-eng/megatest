package org.seedhecode.incidentassistant.data

import org.junit.Assert.*
import org.junit.Test

class FieldRoleTest {
    @Test fun `roles resolve taxonomy and Python fixture names case-insensitively`() {
        assertEquals(FieldRole.DESCRIPTION, FieldRole.of("description"))
        assertEquals(FieldRole.DESCRIPTION, FieldRole.of("Description"))
        assertEquals(FieldRole.SEVERITY, FieldRole.of("Severity"))
        assertEquals(FieldRole.LOCATION, FieldRole.of("space_id"))
        assertNull(FieldRole.of("building"))
        assertNull(FieldRole.of("floor"))
        assertNull(FieldRole.of("leak_source"))
    }

    @Test fun `location signal is the space_id answer with a leading article dropped`() {
        assertEquals("Pantry", FieldRole.locationSignal(mapOf("building" to "N", "floor" to "3", "space_id" to "Pantry")))
        assertEquals("pantry", FieldRole.locationSignal(mapOf("Space_ID" to "  the pantry ")))
        assertEquals("Conference Room 2", FieldRole.locationSignal(mapOf("space_id" to "The Conference Room 2")))
        assertEquals("theatre", FieldRole.locationSignal(mapOf("space_id" to "theatre"))) // only the word "the" is dropped
        assertNull(FieldRole.locationSignal(mapOf("building" to "N", "floor" to "3")))
        assertNull(FieldRole.locationSignal(mapOf("space_id" to null)))
        assertNull(FieldRole.locationSignal(mapOf("space_id" to "   ")))
    }

    @Test fun `valueOf skips blank values and dependencies match the Python map`() {
        assertEquals("leak", FieldRole.valueOf(FieldRole.DESCRIPTION, mapOf("Description" to "leak")))
        assertNull(FieldRole.valueOf(FieldRole.DESCRIPTION, mapOf("description" to "  ")))
        assertEquals(mapOf(FieldRole.DESCRIPTION to listOf(FieldRole.PRIORITY), FieldRole.SEVERITY to listOf(FieldRole.PRIORITY)),
            FieldRole.DEPENDENCIES)
    }
}
