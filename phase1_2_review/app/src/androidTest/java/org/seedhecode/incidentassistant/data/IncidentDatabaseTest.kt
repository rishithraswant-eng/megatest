package org.seedhecode.incidentassistant.data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncidentDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "incident_assistant_test.db"
    private lateinit var helper: IncidentDatabase

    @Before fun open() { context.deleteDatabase(name); helper = IncidentDatabase(context, name) }
    @After fun close() { helper.close(); context.deleteDatabase(name) }

    @Test fun foreignKeysAreOnForReadAndWriteConnections() {
        val write = helper.writableDatabase.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); it.getLong(0) }
        val read = helper.readableDatabase.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); it.getLong(0) }
        Log.i("IncidentDatabaseTest", "PRAGMA foreign_keys -> writable=$write readable=$read")
        assertEquals(1L, write); assertEquals(1L, read)
    }

    @Test fun allSchemaTablesExist() {
        val tables = helper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(IncidentSchema.TABLES.toSet(), tables)
        assertEquals(16, tables.size)
    }

    @Test fun incidentZoneIsNullable() {
        val notNull = helper.readableDatabase.rawQuery("PRAGMA table_info(incidents)", null).use { c ->
            while (c.moveToNext()) if (c.getString(c.getColumnIndexOrThrow("name")) == "zone_id")
                return@use c.getInt(c.getColumnIndexOrThrow("notnull"))
            error("incidents.zone_id missing")
        }
        assertEquals(0, notNull)
    }

    @Test fun danglingReferenceIsRejected() {
        try {
            helper.writableDatabase.insertOrThrow("zones", null, ContentValues().apply {
                put("zone_id", "z1"); put("org_id", "missing-org"); put("name", "Pantry")
                put("polygon_json", "[]"); put("created_at", NOW)
            })
            fail("Insert with a missing organization must violate the foreign key.")
        } catch (_: SQLiteConstraintException) { }
    }

    @Test fun incidentWithUnknownLocationIsAccepted() {
        val db = helper.writableDatabase
        seedParents(db)
        db.insertOrThrow("incidents", null, ContentValues().apply {
            put("incident_id", "i1"); put("org_id", "o1"); put("session_id", "s1"); put("reporter_user_id", "u1")
            putNull("zone_id"); put("incident_type_id", "t1"); put("department_id", "d1")
            put("description_en", "Water on the floor"); put("original_lang", "en"); put("reported_at", NOW)
            put("priority", "Medium"); put("confirmed_revision", 0)
        })
        val zone = db.rawQuery("SELECT zone_id FROM incidents WHERE incident_id='i1'", null).use { it.moveToFirst(); it.isNull(0) }
        assertTrue(zone)
    }

    private fun seedParents(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO organizations VALUES('o1','Org','$NOW','plan.png',100,100)")
        db.execSQL("INSERT INTO departments VALUES('d1','o1','Facilities',NULL)")
        db.execSQL("INSERT INTO incident_types VALUES('t1','o1','Water Leak','d1','Medium',NULL,NULL)")
        db.execSQL("INSERT INTO users VALUES('u1','o1','Reporter','employee',NULL,'auto')")
        db.execSQL("INSERT INTO draft_sessions VALUES('s1','o1','u1','$NOW','$NOW',0,'SUBMITTED','t1',NULL,0)")
    }

    private companion object { const val NOW = "2026-10-11T00:00:00Z" }
}
