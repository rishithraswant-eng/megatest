package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds from the shipped incident_taxonomy.json asset, not a fixture. */
@RunWith(AndroidJUnit4::class)
class TaxonomySeederTest : DatabaseTest() {
    private lateinit var taxonomy: JSONObject

    @Before fun seed() {
        taxonomy = context.assets.open("incident_taxonomy.json").bufferedReader().use { JSONObject(it.readText()) }
        TaxonomySeeder(database, taxonomy).seedIfEmpty()
    }

    private fun fieldNames(typeId: String, kind: String) = db.rawQuery(
        "SELECT field_name FROM form_fields WHERE incident_type_id = ? AND field_kind = ? ORDER BY display_order", arrayOf(typeId, kind),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun seedsEveryTaxonomyEntity() {
        assertEquals(1L, count("SELECT COUNT(*) FROM organizations"))
        assertEquals(taxonomy.getJSONArray("departments").length().toLong(), count("SELECT COUNT(*) FROM departments"))
        assertEquals(28L, count("SELECT COUNT(*) FROM incident_types"))
        assertEquals("P1", query("SELECT default_priority FROM incident_types WHERE incident_type_id = 'electrical_hazard'"))
        assertEquals("FAC", query("SELECT department_id FROM incident_types WHERE incident_type_id = 'water_leak'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM incident_types WHERE disambiguation_hint IS NULL"))
        assertEquals("1", query("SELECT is_active FROM schema_versions WHERE artifact_name = 'incident_taxonomy' AND version_label = ?",
            taxonomy.getJSONObject("meta").getString("version")))
    }

    @Test fun waterLeakFormFollowsAskOrder() {
        assertEquals(listOf("description", "water_spread", "near_electrical", "building", "floor", "space_id", "leak_source", "occurred_at"),
            fieldNames("water_leak", "required"))
        assertEquals(listOf("immediate_actions_taken"), fieldNames("water_leak", "clarifying"))
        assertEquals("enum", query("SELECT data_type FROM form_fields WHERE field_id = 'water_leak.building'"))
        assertEquals("[\"N\",\"QRC\"]", query("SELECT enum_values_json FROM form_fields WHERE field_id = 'water_leak.building'"))
        assertEquals("boolean", query("SELECT data_type FROM form_fields WHERE field_id = 'water_leak.near_electrical'"))
    }

    @Test fun everyTypeAsksLocationAndUsesOnlyTaxonomyFieldNames() {
        val known = taxonomy.getJSONObject("common_fields").keys().asSequence().toSet() +
            taxonomy.getJSONObject("field_library").keys().asSequence().toSet()
        val types = taxonomy.getJSONArray("incident_types")
        for (i in 0 until types.length()) {
            val id = types.getJSONObject(i).getString("id")
            val required = fieldNames(id, "required")
            assertTrue(id, required.containsAll(listOf("description", "building", "floor", "space_id", "occurred_at")))
            assertTrue(id, (required + fieldNames(id, "clarifying")).all { it in known })
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM form_fields WHERE prompt_template = ''"))
    }

    private fun demoZones() = context.assets.open("demo_zones.json").bufferedReader().use { JSONObject(it.readText()) }

    @Test fun demoZonesCoverTheRoomsTheTaxonomyNames() {
        // This setUp seeded the taxonomy without zones, like a database created before zones existed.
        assertEquals(0L, count("SELECT COUNT(*) FROM zones"))
        TaxonomySeeder(database, taxonomy).seedDemoZones(demoZones())
        assertEquals(7L, count("SELECT COUNT(*) FROM zones WHERE org_id = ?", TaxonomySeeder.ORG_ID))
        for (room in listOf("conference room 2", "pantry", "server room", "rf lab", "cafeteria", "men's washroom", "parking"))
            assertNotNull(room, query("SELECT zone_id FROM zones WHERE name = ? COLLATE NOCASE", room))
        assertEquals("N-1", query("SELECT floor FROM zones WHERE zone_id = 'zone_n1_pantry'"))
        assertEquals("N", query("SELECT floor FROM zones WHERE zone_id = 'zone_n0_parking'"))
        val types = taxonomy.getJSONArray("incident_types")
        val label = (0 until types.length()).map { types.getJSONObject(it) }.associate { it.getString("id") to it.getString("label") }
        assertEquals(org.json.JSONArray(listOf("water_leak", "pest_sighting", "spill_cleanup").map(label::getValue)).toString(),
            query("SELECT category_hints FROM zones WHERE zone_id = 'zone_n1_pantry'"))
        assertEquals("1000", query("SELECT floorplan_w_px FROM organizations"))
        assertEquals(0L, count("SELECT COUNT(*) FROM zones WHERE polygon_json = '[]'"))

        TaxonomySeeder(database, taxonomy).seedDemoZones(demoZones())
        assertEquals(7L, count("SELECT COUNT(*) FROM zones"))
    }

    @Test fun reseedingIsANoOp() {
        val before = count("SELECT COUNT(*) FROM form_fields")
        TaxonomySeeder(database, taxonomy).seedIfEmpty()
        assertEquals(before, count("SELECT COUNT(*) FROM form_fields"))
        assertEquals(1L, count("SELECT COUNT(*) FROM schema_versions"))
    }
}
