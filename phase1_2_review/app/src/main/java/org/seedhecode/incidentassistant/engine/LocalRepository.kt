package org.seedhecode.incidentassistant.engine

import android.content.ContentValues
import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import org.seedhecode.core.inference.*

/** Phase 2 local fixture and diagnostics DB; does not own Phase 3-4 drafts/submission. */
class LocalRepository(private val context: Context) : SQLiteOpenHelper(context, "engine-fixtures.db", null, 1), LocalTools {
    private var metricWrites = 0
    val taxonomy = JSONObject(context.assets.open("incident_taxonomy.json").bufferedReader().use { it.readText() })
    val incidentIds = taxonomy.getJSONArray("incident_types").let { a ->
        (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet()
    }
    val fieldNames = setOf("description") + listOf("common_fields", "field_library").flatMap {
        taxonomy.getJSONObject(it).keys().asSequence().toList()
    }.filterNot { it in setOf("incident_type", "reporter") }.toSet()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE zones(zone_id TEXT PRIMARY KEY,name TEXT NOT NULL COLLATE NOCASE,building TEXT NOT NULL,floor INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(incident_id TEXT PRIMARY KEY,zone_id TEXT NOT NULL,incident_type TEXT NOT NULL,priority TEXT NOT NULL,reported_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tool_calls(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT,turn_id INTEGER,draft_revision INTEGER,name TEXT,args TEXT,result TEXT,elapsed_ms REAL,created_at INTEGER)")
        db.execSQL("CREATE TABLE engine_turns(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT,turn_id INTEGER,draft_revision INTEGER,result TEXT,elapsed_ms INTEGER,created_at INTEGER)")
        db.execSQL("CREATE TABLE metrics(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,value_ms REAL,created_at INTEGER)")
        db.execSQL("INSERT INTO zones VALUES('demo-conf-2','Conference Room 2','N',1)")
        db.execSQL("INSERT INTO zones VALUES('demo-pantry','Pantry','N',1)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun stringArg(args: Map<String, Any?>, name: String): String {
        val value = args[name]
        require(value is String && value.isNotBlank() && value.length <= 512) { "Invalid $name." }
        return value
    }
    private fun keys(args: Map<String, Any?>, expected: Set<String>) {
        require(args.keys == expected) { "Tool argument shape mismatch." }
    }

    @Synchronized override fun execute(key: TurnKey, request: ToolRequest): ToolResult {
        val started = System.nanoTime()
        val result = try {
            when (request.name) {
                "get_form_fields" -> {
                    keys(request.arguments, setOf("incident_type_id"))
                    val id = stringArg(request.arguments, "incident_type_id")
                    val types = taxonomy.getJSONArray("incident_types")
                    val type = (0 until types.length()).map { types.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
                        ?: error("Unknown configured incident type.")
                    val common = taxonomy.getJSONObject("common_fields")
                    val library = taxonomy.getJSONObject("field_library")
                    fun fields(names: List<String>) = JSONArray(names.distinct().map { name ->
                        val config = common.optJSONObject(name) ?: library.optJSONObject(name) ?: error("Unknown field $name")
                        JSONObject(config.toString()).put("field_name", name)
                    })
                    val commonRequired = common.keys().asSequence().filter {
                        common.getJSONObject(it).optBoolean("required") && it !in setOf("incident_type", "reporter")
                    }.toList()
                    fun names(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
                    JSONObject().put("incident_type_id", id).put("department_id", type.getString("dept"))
                        .put("required", fields(commonRequired + names(type.getJSONArray("required"))))
                        .put("clarifying", fields(names(type.getJSONArray("optional"))))
                }
                "get_zone_details" -> {
                    keys(request.arguments, setOf("zone_name"))
                    val name = stringArg(request.arguments, "zone_name")
                    readableDatabase.rawQuery("SELECT zone_id,name,building,floor FROM zones WHERE name = ? COLLATE NOCASE", arrayOf(name)).use { cursor ->
                        val candidates = JSONArray()
                        while (cursor.moveToNext()) candidates.put(JSONObject().put("zone_id", cursor.getString(0))
                            .put("name", cursor.getString(1)).put("building", cursor.getString(2)).put("floor", cursor.getInt(3)))
                        // Fixture locations require user confirmation; never silently infer building/floor.
                        JSONObject().put("status", if (candidates.length() == 1) "requires_confirmation" else if (candidates.length() == 0) "unresolved" else "ambiguous")
                            .put("candidates", candidates).put("fixture", true)
                    }
                }
                "get_zone_incident_history" -> {
                    keys(request.arguments, setOf("zone_id", "lookback_days"))
                    val zone = stringArg(request.arguments, "zone_id")
                    val rawDays = request.arguments["lookback_days"]
                    require(rawDays is Number && rawDays.toDouble() == rawDays.toInt().toDouble() && rawDays.toInt() in 1..365)
                    val since = System.currentTimeMillis() - rawDays.toInt() * 86_400_000L
                    readableDatabase.rawQuery("SELECT incident_id,incident_type,priority,reported_at FROM history WHERE zone_id=? AND reported_at>=? ORDER BY reported_at DESC LIMIT 50", arrayOf(zone, since.toString())).use { cursor ->
                        val rows = JSONArray()
                        while (cursor.moveToNext()) rows.put(JSONObject().put("incident_id", cursor.getString(0))
                            .put("incident_type", cursor.getString(1)).put("priority", cursor.getString(2)).put("reported_at", cursor.getLong(3)))
                        JSONObject().put("incidents", rows).put("fixture", true)
                    }
                }
                else -> error("Tool is not allowlisted: ${request.name}")
            }
        } catch (error: Exception) { JSONObject().put("error", error.message ?: "Local tool failed.") }
        val output = result.toString()
        writableDatabase.insertOrThrow("tool_calls", null, ContentValues().apply {
            put("session_id", key.sessionId); put("turn_id", key.turnId); put("draft_revision", key.draftRevision)
            put("name", request.name); put("args", JSONObject(request.arguments).toString()); put("result", output)
            put("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0); put("created_at", System.currentTimeMillis())
        })
        return ToolResult(request.name, request.arguments, output)
    }

    @Synchronized fun save(result: TurnResult): JSONObject {
        val json = result.toJson()
        writableDatabase.insertOrThrow("engine_turns", null, ContentValues().apply {
            put("session_id", result.key.sessionId); put("turn_id", result.key.turnId)
            put("draft_revision", result.key.draftRevision); put("result", json.toString())
            put("elapsed_ms", result.elapsedMs); put("created_at", System.currentTimeMillis())
        })
        return json
    }
    @Synchronized fun metric(name: String, millis: Double) {
        if (!millis.isFinite() || millis < 0) return
        writableDatabase.insertOrThrow("metrics", null, ContentValues().apply {
            put("name", name); put("value_ms", millis); put("created_at", System.currentTimeMillis())
        })
        if (++metricWrites % 100 == 0) writableDatabase.execSQL("DELETE FROM metrics WHERE id < (SELECT MAX(id)-10000 FROM metrics)")
    }
    @Synchronized fun diagnostics(): JSONObject {
        val metrics = JSONObject()
        for ((name, budget) in mapOf("listening_feedback" to 100, "barge_in_stop" to 150,
            "cached_question_audio" to 1000, "dynamic_question_audio" to 2000, "raw_turn_processing" to 0)) {
            val values = mutableListOf<Double>()
            readableDatabase.rawQuery("SELECT value_ms FROM metrics WHERE name=? ORDER BY value_ms", arrayOf(name)).use { c ->
                while (c.moveToNext()) values.add(c.getDouble(0))
            }
            val p95 = if (values.isEmpty()) null else values[(kotlin.math.ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
            metrics.put(name, JSONObject().put("sample_count", values.size).put("p95_ms", p95 ?: JSONObject.NULL)
                .put("target_ms", if (budget == 0) JSONObject.NULL else budget).put("meets_target", if (budget == 0 || p95 == null) JSONObject.NULL else p95 < budget))
        }
        // Additional measurements are labelled without conflating them with full response budgets.
        val components = JSONObject()
        for (name in listOf("cfc_filter", "laya_classifier", "llm_and_tools", "tts_enqueue_to_start", "barge_in_stop_request")) {
            val values = mutableListOf<Double>()
            readableDatabase.rawQuery("SELECT value_ms FROM metrics WHERE name=? ORDER BY value_ms", arrayOf(name)).use { c ->
                while (c.moveToNext()) values.add(c.getDouble(0))
            }
            val p95 = if (values.isEmpty()) null else values[kotlin.math.ceil(values.size * 0.95).toInt() - 1]
            components.put(name, JSONObject().put("sample_count", values.size).put("p95_ms", p95 ?: JSONObject.NULL))
        }
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject().put("contract_version", 1).put("device", JSONObject().put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unavailable")
            .put("os_build", Build.DISPLAY).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("ram_bytes", memory.totalMem).put("available_ram_bytes", memory.availMem)
            .put("process_pss_kb", Debug.getPss()).put("battery_temperature_tenths_c", battery?.getIntExtra("temperature", -1) ?: -1)
            .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL))
            .put("metrics", metrics).put("components", components).put("backend", "CPU reference; no Hexagon validation")
            .put("expected_kit", "Snapdragon 8 Elite SM8750P / QAIRT-SNPE 2.47")
            .put("runtime_verified", false).put("power_draw_measured", false)
            .put("submission", "not implemented in phase 2; engine fixture only")
    }
    @Synchronized fun export(): String {
        val turns = JSONArray()
        readableDatabase.rawQuery("SELECT result FROM engine_turns ORDER BY id DESC LIMIT 100", null).use { c ->
            while (c.moveToNext()) turns.put(JSONObject(c.getString(0)))
        }
        val calls = JSONArray()
        readableDatabase.rawQuery("SELECT session_id,turn_id,draft_revision,name,args,result,elapsed_ms FROM tool_calls ORDER BY id DESC LIMIT 500", null).use { c ->
            while (c.moveToNext()) calls.put(JSONObject().put("session_id", c.getString(0)).put("turn_id", c.getLong(1))
                .put("draft_revision", c.getLong(2)).put("function_name", c.getString(3)).put("arguments", JSONObject(c.getString(4)))
                .put("result", JSONObject(c.getString(5))).put("elapsed_ms", c.getDouble(6)))
        }
        return diagnostics().put("turns", turns).put("function_calls", calls).toString(2)
    }
}

fun TurnResult.toJson(): JSONObject = JSONObject().put("contract_version", 1)
    .put("session_id", key.sessionId).put("turn_id", key.turnId).put("draft_revision", key.draftRevision)
    .put("transcript", JSONObject().put("text", transcript.text).put("source_text", transcript.sourceText)
        .put("is_final", transcript.isFinal).put("lang_detected", transcript.language))
    .put("dialogue_act", extraction.dialogueAct?.wire ?: JSONObject.NULL)
    .put("candidate_incident_types", JSONArray(classification.candidates.map {
        JSONObject().put("incident_type_id", it.incidentTypeId).put("confidence", it.confidence)
    }))
    .put("candidate_fields", JSONArray(extraction.fields.map {
        JSONObject().put("field_name", it.name).put("value", it.value ?: JSONObject.NULL).put("confidence", it.confidence)
            .put("provenance", it.provenance).put("status", it.status)
            .put("evidence", JSONObject().put("text", it.evidence.text).put("start", it.evidence.start).put("end", it.evidence.end))
    }))
    .put("function_calls_made", JSONArray(functionCalls.map {
        JSONObject().put("function_name", it.name).put("arguments", JSONObject(it.arguments)).put("result", JSONObject(it.result))
    })).put("assistant_followup_text", assistantFollowupText ?: JSONObject.NULL)
    .put("assistant_followup_audio_ref", assistantFollowupAudioRef ?: JSONObject.NULL)
    .put("distinct_incidents_mentioned", extraction.distinctIncidentsMentioned)
    .put("user_wants_to_stop_clarifying", extraction.userWantsToStopClarifying)
    .put("metadata", JSONObject().put("classification_outcome", classification.outcome.wire)
        .put("classification_source", if (classification.probabilities.isEmpty()) "no_laya_prediction" else "laya")
        .put("needs_confirmation", classification.needsConfirmation).put("probabilities", JSONObject(classification.probabilities))
        .put("incident_groups", JSONArray(extraction.incidentGroups)).put("elapsed_ms", elapsedMs)
        .put("stage_timings_ms", JSONObject(stageTimingsMs)).put("stage_status", JSONObject(stageStatus))
        .put("source_alignment", "whole transcript; word alignment not provided")
        .put("model_versions", JSONObject(modelVersions)).put("relevance", JSONArray(relevance.map {
            JSONObject().put("sentence", it.sentence).put("relevant", it.relevant).put("probability", it.probability)
        })))
