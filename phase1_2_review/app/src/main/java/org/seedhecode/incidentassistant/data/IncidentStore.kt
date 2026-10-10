package org.seedhecode.incidentassistant.data

import android.content.Context
import org.json.JSONObject

/** One seeded incident database per process, shared by the reporter and admin screens. Open off the main thread. */
object IncidentStore {
    @Volatile private var database: IncidentDatabase? = null

    fun open(context: Context): IncidentDatabase = database ?: synchronized(this) {
        database ?: IncidentDatabase(context.applicationContext).also { opened ->
            fun asset(name: String) = context.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
            TaxonomySeeder(opened, asset("incident_taxonomy.json")).run {
                seedIfEmpty()
                seedDemoZones(asset("demo_zones.json"))
            }
            database = opened
        }
    }
}
