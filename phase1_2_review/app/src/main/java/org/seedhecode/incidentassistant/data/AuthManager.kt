package org.seedhecode.incidentassistant.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256, 100k iterations, stored as "salt_hex:hash_hex" (same format as backend/auth_manager.py). */
object PasswordHasher {
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun hash(password: String, salt: ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)): String =
        "${salt.toHex()}:${derive(password, salt).toHex()}"

    fun verify(password: String, stored: String): Boolean = try {
        val (saltHex, hashHex) = stored.split(':').also { require(it.size == 2) }
        MessageDigest.isEqual(derive(password, saltHex.fromHex()), hashHex.fromHex())
    } catch (_: Exception) { false }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray {
        require(length % 2 == 0) { "Odd-length hex." }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

data class AdminSession(val adminId: String, val roleLevel: String, val orgId: String, val departmentId: String?)

data class IncidentListing(
    val incidentId: String, val typeLabel: String, val departmentId: String, val priority: String, val status: IncidentStatus,
    val zoneName: String?, val description: String, val reportedAt: String, val localSubmissionOnly: Boolean,
    /** Null when SUBMIT could not resolve a zone; such incidents get no map pin. */
    val zoneId: String? = null,
)

/** Admin login and department RBAC, ported from backend/auth_manager.py. */
class AuthManager(private val database: IncidentDatabase) {

    /** [username] matches users.user_id or users.display_name. */
    fun loginAdmin(username: String, password: String): AdminSession? {
        val row = database.readableDatabase.rawQuery("SELECT a.admin_id, a.password_hash, a.role_level, a.department_id, u.org_id " +
            "FROM admin_credentials a JOIN users u ON a.user_id = u.user_id WHERE u.user_id = ? OR u.display_name = ?",
            arrayOf(username, username)).use { c ->
            if (!c.moveToFirst()) return null
            listOf(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getString(4))
        }
        if (!PasswordHasher.verify(password, row[1]!!)) return null
        return AdminSession(row[0]!!, row[2]!!, row[4]!!, row[3])
    }

    fun hasAnyAdmin(orgId: String): Boolean = database.readableDatabase.string(
        "SELECT a.admin_id FROM admin_credentials a JOIN users u ON a.user_id = u.user_id WHERE u.org_id = ? LIMIT 1", orgId) != null

    /** Creates an admin user and credential; [departmentId] is required for department_admin only. */
    fun createAdmin(orgId: String, displayName: String, password: String, roleLevel: String, departmentId: String? = null): String {
        require(roleLevel in setOf("super_admin", "facilities_admin", "department_admin")) { "Unknown role $roleLevel" }
        require((roleLevel == "department_admin") == (departmentId != null)) { "Only department_admin has a department." }
        require(displayName.isNotBlank() && password.length >= 8) { "Name is required and the password needs at least 8 characters." }
        val userId = "user_" + newId(); val adminId = "adm_" + newId()
        database.writableDatabase.inTransaction {
            execSQL("INSERT INTO users (user_id, org_id, display_name, role) VALUES (?, ?, ?, 'admin')", arrayOf(userId, orgId, displayName.trim()))
            execSQL("INSERT INTO admin_credentials (admin_id, user_id, password_hash, role_level, department_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)", arrayOf(adminId, userId, PasswordHasher.hash(password), roleLevel, departmentId, now()))
        }
        return adminId
    }

    /** Submitted incidents of the admin's org, filtered per department through [checkDepartmentAccess]. */
    fun visibleIncidents(admin: AdminSession): List<IncidentListing> {
        val rows = listings("i.org_id = ?", admin.orgId)
        val allowed = rows.map { it.departmentId }.distinct().filter { checkDepartmentAccess(admin.adminId, it) }.toSet()
        return rows.filter { it.departmentId in allowed }
    }

    /**
     * Moves an incident one step forward (Open -> In Progress -> Resolved) and returns the stored row.
     * Refused when the admin cannot access the incident's department, when the step is not the next one,
     * or when someone else changed the status since [from] was read.
     */
    fun updateIncidentStatus(admin: AdminSession, incidentId: String, from: IncidentStatus, to: IncidentStatus): IncidentListing {
        val db = database.writableDatabase
        return db.inTransaction {
            val current = listings("i.incident_id = ? AND i.org_id = ?", incidentId, admin.orgId).singleOrNull()
                ?: throw IllegalArgumentException("Incident not found.")
            if (!checkDepartmentAccess(admin.adminId, current.departmentId))
                throw SecurityException("You cannot update incidents for ${current.departmentId}.")
            require(from.next == to) { "Status can only move from ${from.wire} to ${from.next?.wire ?: "nowhere"}." }
            val changed = compileStatement("UPDATE incidents SET status = ? WHERE incident_id = ? AND status = ?").use {
                it.bindString(1, to.wire); it.bindString(2, incidentId); it.bindString(3, from.wire); it.executeUpdateDelete()
            }
            check(changed == 1) { "This incident is now ${current.status.wire}; refresh and try again." }
            listings("i.incident_id = ?", incidentId).single()
        }
    }

    private fun listings(where: String, vararg args: String): List<IncidentListing> = database.readableDatabase.rawQuery(
        "SELECT i.incident_id, t.label, i.department_id, i.priority, i.status, z.name, i.description_en, i.reported_at, " +
            "i.local_submission_only, i.zone_id FROM incidents i JOIN incident_types t ON i.incident_type_id = t.incident_type_id " +
            "LEFT JOIN zones z ON i.zone_id = z.zone_id WHERE $where ORDER BY i.reported_at DESC", args,
    ).use { c ->
        buildList { while (c.moveToNext()) add(IncidentListing(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
            IncidentStatus.fromWire(c.getString(4)), if (c.isNull(5)) null else c.getString(5), c.getString(6), c.getString(7),
            c.getInt(8) == 1, if (c.isNull(9)) null else c.getString(9))) }
    }

    /** Every zone of the admin's org plus the incidents this admin may see; zones themselves are not restricted. */
    fun mapData(admin: AdminSession): MapData {
        val db = database.readableDatabase
        val (width, height) = db.rawQuery("SELECT floorplan_w_px, floorplan_h_px FROM organizations WHERE org_id = ?",
            arrayOf(admin.orgId)).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }
        val zones = db.rawQuery("SELECT zone_id, name, floor, polygon_json FROM zones WHERE org_id = ? ORDER BY name",
            arrayOf(admin.orgId)).use { c ->
            buildList { while (c.moveToNext()) add(MapZone(c.getString(0), c.getString(1),
                if (c.isNull(2)) null else c.getString(2), parsePolygon(c.getString(3)))) }
        }
        return MapData(width.toFloat(), height.toFloat(), zones, visibleIncidents(admin))
    }

    /** [[x, y], ...] in floorplan pixels; anything unreadable becomes an empty outline, which the map skips. */
    private fun parsePolygon(json: String): List<MapPoint> = runCatching {
        val points = org.json.JSONArray(json)
        (0 until points.length()).map { val p = points.getJSONArray(it); MapPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }
    }.getOrDefault(emptyList())

    /** Org-wide roles see every department in their org; department_admin sees only its own; cross-org is always denied. */
    fun checkDepartmentAccess(adminId: String, departmentId: String): Boolean {
        val db = database.readableDatabase
        val (role, adminDepartment, adminOrg) = db.rawQuery("SELECT a.role_level, a.department_id, u.org_id FROM admin_credentials a " +
            "JOIN users u ON a.user_id = u.user_id WHERE a.admin_id = ?", arrayOf(adminId)).use { c ->
            if (!c.moveToFirst()) return false
            Triple(c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getString(2))
        }
        val departmentOrg = db.string("SELECT org_id FROM departments WHERE department_id = ?", departmentId) ?: return false
        if (departmentOrg != adminOrg) return false
        return when (role) {
            "super_admin", "facilities_admin" -> true
            "department_admin" -> adminDepartment == departmentId
            else -> false
        }
    }
}
