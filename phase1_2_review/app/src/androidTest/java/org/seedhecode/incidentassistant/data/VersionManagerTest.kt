package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ports backend/tests/test_versions_and_validation.py section 4.4. */
@RunWith(AndroidJUnit4::class)
class VersionManagerTest : DatabaseTest() {
    private lateinit var versions: VersionManager
    @Before fun setUp() { versions = VersionManager(database) }

    private fun active(id: String) = query("SELECT is_active FROM schema_versions WHERE version_id = ?", id)
    private fun rollbackOf(id: String) = query("SELECT rollback_of FROM schema_versions WHERE version_id = ?", id)

    @Test fun activateThenRollback() {
        val v1 = versions.publishVersion("schema", "form_fields", "v1.0")
        val v2 = versions.publishVersion("schema", "form_fields", "v1.1")
        versions.activateVersion(v1); versions.activateVersion(v2)
        assertEquals("0", active(v1)); assertEquals("1", active(v2))
        versions.rollbackVersion(v2, v1)
        assertEquals("1", active(v1)); assertEquals(v2, rollbackOf(v1))
        assertEquals("0", active(v2)); assertNull(rollbackOf(v2))
    }

    @Test fun activationDeactivatesEverySiblingNotJustTheLatest() {
        val ids = (1..3).map { versions.publishVersion("llm_model", "qwen", "v$it") }
        // Corrupt state: two siblings active at once. Activation must clear both.
        exec("UPDATE schema_versions SET is_active = 1 WHERE version_id IN (?, ?)", ids[0], ids[1])
        val other = versions.publishVersion("llm_model", "whisper", "v1").also { versions.activateVersion(it) }
        versions.activateVersion(ids[2])
        assertEquals(listOf("0", "0", "1"), ids.map(::active))
        assertEquals("1", active(other)) // a different artifact is untouched
    }

    @Test fun rollbackAcrossArtifactsIsRejected() {
        val a = versions.publishVersion("schema", "form_fields", "v1")
        val b = versions.publishVersion("schema", "routing_rules", "v1")
        versions.activateVersion(a)
        try { versions.rollbackVersion(a, b); fail("Expected rejection") } catch (e: IllegalArgumentException) { assertEquals("Artifact mismatch", e.message) }
        assertEquals("1", active(a)); assertEquals("0", active(b))
    }

    @Test fun unknownVersionIsRejected() {
        try { versions.activateVersion("ver_missing"); fail("Expected rejection") } catch (e: IllegalArgumentException) { assertEquals("Version not found", e.message) }
    }
}
