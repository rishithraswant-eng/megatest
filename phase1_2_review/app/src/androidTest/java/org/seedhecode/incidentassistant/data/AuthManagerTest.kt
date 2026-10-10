package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ports backend/tests/test_submit_and_auth.py section 4.3, plus cross-org and Android-provider checks. */
@RunWith(AndroidJUnit4::class)
class AuthManagerTest : DatabaseTest() {
    private lateinit var auth: AuthManager

    @Before fun seed() {
        seedOrg()
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', 'org_1', 'Maintenance')")
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d2', 'org_1', 'IT')")
        admin("a_d1", "admin_d1", "Admin D1", "pass123", "department_admin", "d1")
        admin("a_sup", "admin_super", "Super Admin", "super456", "super_admin", null)
        admin("a_fac", "admin_fac", "Facilities", "fac789", "facilities_admin", null)
        seedOrg("org_2", "user_2")
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d9', 'org_2', 'Other Org Dept')")
        auth = AuthManager(database)
    }

    private fun admin(adminId: String, userId: String, name: String, password: String, role: String, department: String?) {
        exec("INSERT INTO users (user_id, org_id, display_name, role) VALUES (?, 'org_1', ?, 'admin')", userId, name)
        exec("INSERT INTO admin_credentials (admin_id, user_id, password_hash, role_level, department_id, created_at) VALUES (?, ?, ?, ?, ?, '2026')",
            adminId, userId, PasswordHasher.hash(password), role, department)
    }

    @Test fun a_successfulLogin() {
        assertEquals(AdminSession("a_d1", "department_admin", "org_1", "d1"), auth.loginAdmin("admin_d1", "pass123"))
        assertEquals("a_sup", auth.loginAdmin("Super Admin", "super456")?.adminId) // display_name also matches
    }

    @Test fun b_wrongPasswordFails() {
        assertNull(auth.loginAdmin("admin_d1", "wrong"))
        assertNull(auth.loginAdmin("nobody", "pass123"))
    }

    @Test fun c_departmentAdminBlockedFromOtherDepartment() {
        assertTrue(auth.checkDepartmentAccess("a_d1", "d1"))
        assertFalse(auth.checkDepartmentAccess("a_d1", "d2"))
    }

    @Test fun d_orgWideAdminsAllowedEverywhereInOrg() {
        for (admin in listOf("a_sup", "a_fac")) {
            assertTrue(auth.checkDepartmentAccess(admin, "d1")); assertTrue(auth.checkDepartmentAccess(admin, "d2"))
        }
    }

    @Test fun crossOrgAlwaysRejected() {
        for (admin in listOf("a_d1", "a_sup", "a_fac")) assertFalse(auth.checkDepartmentAccess(admin, "d9"))
        assertFalse(auth.checkDepartmentAccess("a_sup", "no_such_dept"))
        assertFalse(auth.checkDepartmentAccess("no_such_admin", "d1"))
    }

    @Test fun androidProviderMatchesPythonHashes() {
        for ((password, stored) in PYTHON_VECTORS) {
            assertTrue(PasswordHasher.verify(password, stored))
            assertEquals(stored, PasswordHasher.hash(password, stored.substringBefore(':').chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        }
    }

    companion object {
        /** Produced by backend/auth_manager.hash_password with fixed salts. */
        val PYTHON_VECTORS = listOf(
            "pass123" to "000102030405060708090a0b0c0d0e0f:36122dce28ce33eeefadc593801f657abdfafe3a790da6ca81668b10e8b1ce19",
            "पासवर्ड-é" to
                "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1:d3a444ba89eaf33b354dfcf784e684a5e5d1d4042d0108674951f222e379041c",
        )
    }
}
