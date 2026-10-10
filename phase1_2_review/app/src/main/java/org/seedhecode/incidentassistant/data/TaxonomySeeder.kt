package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Populates an empty incident database from incident_taxonomy.json, so form_fields carry the taxonomy's own
 * field names (the same names the extractor is told to use). Runs once: a database that already has its
 * organization is left untouched.
 */
class TaxonomySeeder(private val database: IncidentDatabase, private val taxonomy: JSONObject) {

    fun seedIfEmpty() {
        val db = database.writableDatabase
        if (db.string("SELECT org_id FROM organizations WHERE org_id = ?", ORG_ID) != null) return
        db.inTransaction {
            val at = now()
            // No floorplan has been supplied yet; the map view must treat an empty path as "no map".
            execSQL("INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) " +
                "VALUES (?, ?, ?, '', 0, 0)", arrayOf(ORG_ID, "Local organization", at))
            execSQL("INSERT INTO users (user_id, org_id, display_name, role, home_zone_id, preferred_lang) " +
                "VALUES (?, ?, 'This device', 'employee', NULL, 'auto')", arrayOf(REPORTER_ID, ORG_ID))
            taxonomy.getJSONArray("departments").objects().forEach { dept ->
                execSQL("INSERT INTO departments (department_id, org_id, name, description) VALUES (?, ?, ?, ?)",
                    arrayOf(dept.getString("id"), ORG_ID, dept.getString("name"), dept.optString("parent_org").ifBlank { null }))
            }
            val common = taxonomy.getJSONObject("common_fields")
            val library = taxonomy.getJSONObject("field_library")
            val buildings = taxonomy.getJSONObject("location_model").getJSONArray("buildings").objects().map { it.getString("code") }
            for (type in taxonomy.getJSONArray("incident_types").objects()) {
                val id = type.getString("id"); val label = type.getString("label")
                execSQL("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority, " +
                    "responsible_roles, disambiguation_hint) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    // The taxonomy has no per-type hints; this generic one is data and can be refined per type.
                    arrayOf(id, ORG_ID, label, type.getString("dept"), type.getString("default_priority"),
                        JSONArray(listOfNotNull(type.optString("role").ifBlank { null })).toString(),
                        "Just to check: is this about ${label.lowercase()}?"))
                val typeRequired = type.getJSONArray("required").strings()
                val safety = typeRequired.filter { library.optJSONObject(it)?.optBoolean("safety") == true }
                // completion_policy.ask_order: safety_critical, location, type_required, occurred_at.
                // description leads because an unparsed narration should be re-asked before anything else.
                val required = (listOf("description") + safety + LOCATION_FIELDS + (typeRequired - safety.toSet()) +
                    commonRequiredRest(common)).distinct()
                var order = 0
                for (name in required) insertField(this, id, name, FieldKind.REQUIRED, common, library, buildings, ++order)
                for (name in type.getJSONArray("optional").strings()) insertField(this, id, name, FieldKind.CLARIFYING, common, library, buildings, ++order)
            }
            // Same connection, nested transaction: the version record commits or rolls back with the seed.
            VersionManager(database).run {
                activateVersion(publishVersion("form_schema", "incident_taxonomy", taxonomy.getJSONObject("meta").getString("version")))
            }
        }
    }

    /**
     * Adds any demo zone not already present (by zone_id), so databases seeded before zones existed pick them
     * up too. zones has no building column, so floor is stored as "<building>-<floor>" (e.g. "N-1"), or just
     * the building when the zone has no floor.
     */
    fun seedDemoZones(demo: JSONObject) {
        val labels = taxonomy.getJSONArray("incident_types").objects().associate { it.getString("id") to it.getString("label") }
        val plan = demo.getJSONObject("floorplan")
        database.writableDatabase.inTransaction {
            execSQL("UPDATE organizations SET floorplan_w_px = ?, floorplan_h_px = ? WHERE org_id = ? AND floorplan_w_px = 0",
                arrayOf(plan.getInt("w_px"), plan.getInt("h_px"), ORG_ID))
            for (zone in demo.getJSONArray("zones").objects()) {
                val hints = zone.getJSONArray("incident_types").strings().map { labels[it] ?: error("Unknown incident type $it in demo zones.") }
                val floor = if (zone.isNull("floor")) zone.getString("building") else "${zone.getString("building")}-${zone.getInt("floor")}"
                execSQL("INSERT OR IGNORE INTO zones (zone_id, org_id, name, floor, polygon_json, category_hints, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf(zone.getString("id"), ORG_ID, zone.getString("name"), floor,
                    zone.getJSONArray("polygon").toString(), JSONArray(hints).toString(), now()))
            }
        }
    }

    private fun commonRequiredRest(common: JSONObject): List<String> = common.keys().asSequence()
        .filter { common.getJSONObject(it).optBoolean("required") && it !in NOT_ASKED && it != "description" && it !in LOCATION_FIELDS }
        .toList()

    private fun insertField(db: SQLiteDatabase, typeId: String, name: String, kind: FieldKind, common: JSONObject,
        library: JSONObject, buildings: List<String>, order: Int) {
        val spec = common.optJSONObject(name) ?: library.optJSONObject(name) ?: error("Taxonomy field $name is not defined.")
        val rawType = spec.optString("type")
        val enumValues = when {
            rawType == "enum" -> spec.getJSONArray("values").toString()
            rawType == "enum_ref:location_model.buildings" -> JSONArray(buildings).toString()
            else -> null
        }
        val dataType = when {
            enumValues != null -> "enum"
            rawType == "bool" -> "boolean"
            rawType.startsWith("datetime") -> "datetime"
            else -> "text"
        }
        val prompt = spec.optString("question").ifBlank { FALLBACK_PROMPTS[name] ?: "Can you tell me the ${name.replace('_', ' ')}?" }
        db.execSQL("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, enum_values_json, " +
            "prompt_template, display_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf("$typeId.$name", typeId, name, kind.wire, dataType, enumValues, prompt, order))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    companion object {
        const val ORG_ID = "org_local"
        const val REPORTER_ID = "user_local_reporter"
        private val LOCATION_FIELDS = listOf("building", "floor", "space_id")
        /** Filled by the classifier and the device profile, never asked. */
        private val NOT_ASKED = setOf("incident_type", "reporter")
        private val FALLBACK_PROMPTS = mapOf("description" to "Can you briefly describe what happened?")
    }
}
