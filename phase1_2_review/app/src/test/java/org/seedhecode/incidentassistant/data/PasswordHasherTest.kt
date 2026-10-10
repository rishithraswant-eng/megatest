package org.seedhecode.incidentassistant.data

import org.junit.Assert.*
import org.junit.Test

class PasswordHasherTest {
    /** Produced by backend/auth_manager.hash_password with fixed salts. */
    private val pythonVectors = listOf(
        "pass123" to "000102030405060708090a0b0c0d0e0f:36122dce28ce33eeefadc593801f657abdfafe3a790da6ca81668b10e8b1ce19",
        "पासवर्ड-é" to
            "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1:d3a444ba89eaf33b354dfcf784e684a5e5d1d4042d0108674951f222e379041c",
    )

    @Test fun `hashes match the Python implementation byte for byte`() {
        for ((password, stored) in pythonVectors) {
            val salt = stored.substringBefore(':').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals(stored, PasswordHasher.hash(password, salt))
            assertTrue(PasswordHasher.verify(password, stored))
        }
    }

    @Test fun `random salt is 16 bytes and differs per hash`() {
        val a = PasswordHasher.hash("pass123"); val b = PasswordHasher.hash("pass123")
        assertNotEquals(a, b)
        assertEquals(32, a.substringBefore(':').length); assertEquals(64, a.substringAfter(':').length)
        assertTrue(PasswordHasher.verify("pass123", a)); assertTrue(PasswordHasher.verify("pass123", b))
    }

    @Test fun `wrong password and malformed hashes are rejected`() {
        val stored = pythonVectors[0].second
        assertFalse(PasswordHasher.verify("wrong", stored))
        for (bad in listOf("", "nocolon", "zz:zz", "abc:def", "${stored}:extra")) assertFalse(PasswordHasher.verify("pass123", bad))
    }
}
