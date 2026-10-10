package org.seedhecode.incidentassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Phase 3-4 drafts, form state and submitted incidents; separate from engine-fixtures.db. */
class IncidentDatabase(context: Context, name: String = NAME) :
    SQLiteOpenHelper(context, name, null, IncidentSchema.VERSION) {

    // onConfigure runs for every connection the helper opens; SQLite defaults foreign_keys to OFF.
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) = IncidentSchema.CREATE_STATEMENTS.forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("No migration from schema $oldVersion to $newVersion.")

    override fun onOpen(db: SQLiteDatabase) {
        check(foreignKeys(db) == 1L) { "SQLite foreign key enforcement is off." }
    }

    /** Reads PRAGMA foreign_keys from the live connection: 1 = enforced, 0 = off. */
    fun foreignKeys(db: SQLiteDatabase = readableDatabase): Long =
        db.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); it.getLong(0) }

    companion object { const val NAME = "incident_assistant.db" }
}
