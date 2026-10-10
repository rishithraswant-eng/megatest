package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase

/** Schema/model version activation and rollback, ported from backend/version_manager.py. */
class VersionManager(private val database: IncidentDatabase) {

    fun publishVersion(artifactType: String, artifactName: String, versionLabel: String): String {
        val versionId = "ver_" + newId()
        database.writableDatabase.execSQL("INSERT INTO schema_versions (version_id, artifact_type, artifact_name, version_label, " +
            "is_active, released_at) VALUES (?, ?, ?, ?, 0, ?)", arrayOf(versionId, artifactType, artifactName, versionLabel, now()))
        return versionId
    }

    /** Deactivates every version of the same (artifact_type, artifact_name), then activates [versionId]. */
    fun activateVersion(versionId: String) = database.writableDatabase.inTransaction {
        val (type, name) = artifact(versionId)
        deactivateAll(type, name)
        execSQL("UPDATE schema_versions SET is_active = 1 WHERE version_id = ?", arrayOf(versionId))
    }

    /** Re-activates [targetVersionId] and records it as a rollback of [versionIdToRollback]. */
    fun rollbackVersion(versionIdToRollback: String, targetVersionId: String) = database.writableDatabase.inTransaction {
        val artifact = artifact(versionIdToRollback)
        require(artifact == artifact(targetVersionId)) { "Artifact mismatch" }
        deactivateAll(artifact.first, artifact.second)
        execSQL("UPDATE schema_versions SET is_active = 1, rollback_of = ? WHERE version_id = ?",
            arrayOf(versionIdToRollback, targetVersionId))
    }

    private fun SQLiteDatabase.artifact(versionId: String): Pair<String, String> =
        rawQuery("SELECT artifact_type, artifact_name FROM schema_versions WHERE version_id = ?", arrayOf(versionId)).use { c ->
            require(c.moveToFirst()) { "Version not found" }
            c.getString(0) to c.getString(1)
        }

    private fun SQLiteDatabase.deactivateAll(type: String, name: String) =
        execSQL("UPDATE schema_versions SET is_active = 0 WHERE artifact_type = ? AND artifact_name = ?", arrayOf(type, name))
}
