package org.seedhecode.incidentassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import org.seedhecode.core.audio.ConversationCapture
import org.seedhecode.core.inference.PipelineMode
import org.seedhecode.core.inference.TurnResult
import org.seedhecode.incidentassistant.data.*
import org.seedhecode.incidentassistant.engine.*
import java.util.UUID
import java.util.concurrent.Executors

/** Voice/text incident reporting: engine proposals drive the Phase 3-4 workflow through review and local submit. */
class ConversationActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var captions: TextView
    private lateinit var output: TextView
    private lateinit var modelStatus: TextView
    private lateinit var level: ProgressBar
    private lateinit var listen: Button
    private lateinit var process: Button
    private lateinit var importModels: Button
    private lateinit var loadModels: Button
    private lateinit var text: EditText
    private lateinit var languages: Spinner
    private lateinit var question: TextView
    private lateinit var skipClarifying: Button
    private lateinit var typePanel: LinearLayout
    private lateinit var typeButtons: LinearLayout
    /** True when no Laya classifier is loaded (ASR_LLM bundle), so the reporter picks the incident type. */
    private var manualTypes = true
    /** The turn that left the draft unclassified; its extracted fields are applied once a type is picked. */
    @Volatile private var unclassifiedTurn: TurnResult? = null
    private lateinit var reviewPanel: LinearLayout
    private lateinit var summary: TextView
    private lateinit var confirm: Button
    private lateinit var submit: Button
    private lateinit var correctionField: Spinner
    private lateinit var correctionValue: EditText
    private lateinit var donePanel: LinearLayout
    private lateinit var doneMessage: TextView
    private lateinit var continueLinked: Button
    /** Single lane for every database and model-setup action, so workflow steps apply in order. */
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var models: ModelStore
    private lateinit var repository: LocalRepository
    private var workflow: ReportingWorkflow? = null
    private var step: WorkflowStep? = null
    private var engine: AndroidConversationEngine? = null
    private var capture: ConversationCapture? = null
    private lateinit var speaker: OfflineSpeaker
    private var draftSessionId = UUID.randomUUID().toString()
    private var revision = 0L
    private var listening = false
    private var foreground = false
    private var busy = false
    private var echoCancellation = false
    private var exportPending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelStore(this)
        repository = LocalRepository(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.background))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(value: String, size: Float = 16f, parent: LinearLayout = column): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
        }
        fun button(value: String, parent: LinearLayout = column, action: () -> Unit): Button = Button(this).apply {
            text = value; isAllCaps = false; setOnClickListener { action() }; parent.addView(this)
        }
        fun panel() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; column.addView(this) }

        label("Report an incident", 28f)
        label("Speak naturally and pause to finish a turn. Nothing is saved as an incident until you confirm and submit.")
        status = label("Opening local incident store…")
        modelStatus = label("Models are imported from a local bundle. No automatic downloads.", 13f)
        importModels = button("Import local model bundle") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
            }, IMPORT_MODELS)
        }
        loadModels = button("Load local models") { load() }
        languages = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConversationActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto: English / Hindi / Hinglish", "English", "Hindi / Hinglish"))
            column.addView(this)
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; column.addView(this) }
        listen = button("Start conversation") { if (listening) stopListening() else requestListening() }
        captions = label("Your transcript appears here.", 18f)
        label("Or type your answer", 18f)
        text = EditText(this).apply {
            hint = "Describe the incident or answer the question"; minLines = 2; maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            column.addView(this)
        }
        process = button("Send typed answer") {
            if (text.text.isBlank()) status.text = "Type an answer first."
            else {
                stopListening()
                engine?.processText(text.text.toString()); text.setText("")
                status.text = "Processing with local models…"
            }
        }

        label("Assistant", 18f)
        question = label("Tell me what happened.", 20f)
        button("Repeat question aloud") { step?.question?.let(::speakQuestion) ?: run { status.text = "No question to repeat." } }
        skipClarifying = button("Skip remaining questions") { act { it.skipClarification(draftSessionId) } }.apply { visibility = View.GONE }

        // ASR_LLM bundles have no Laya classifier: the reporter confirms the incident type by hand.
        typePanel = panel()
        label("No classifier is installed, so please choose the incident type:", 16f, typePanel)
        typeButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; typePanel.addView(this) }

        reviewPanel = panel()
        label("Review your report", 20f, reviewPanel)
        summary = label("", 16f, reviewPanel)
        confirm = button("Confirm these details", reviewPanel) { act { it.confirm(draftSessionId) } }
        submit = button("Submit report", reviewPanel) { act { it.submit(draftSessionId) } }
        label("Something wrong? Correct one field:", 14f, reviewPanel)
        correctionField = Spinner(this).apply { reviewPanel.addView(this) }
        correctionValue = EditText(this).apply { hint = "Corrected value"; reviewPanel.addView(this) }
        button("Apply correction", reviewPanel) {
            val field = correctionField.selectedItem as? String ?: return@button
            val value = correctionValue.text.toString()
            act { it.correct(draftSessionId, field, value) }
            correctionValue.setText("")
        }
        button("Cancel this report", reviewPanel) { act { it.cancel(draftSessionId) } }

        donePanel = panel()
        doneMessage = label("", 16f, donePanel)
        continueLinked = button("Continue with the second incident", donePanel) { switchToLinked() }
        button("Start a new report", donePanel) { newDraft(UUID.randomUUID().toString()) }

        label("Latest model proposals", 20f)
        output = label("No model result yet.", 13f).apply { setTextIsSelectable(true) }
        button("Export local evidence and diagnostics") {
            background {
                exportPending = repository.export()
                ui { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "incident-engine-diagnostics.json")
                }, EXPORT_DIAGNOSTICS) }
            }
        }
        speaker = OfflineSpeaker(this, { message -> ui { status.text = message } }, { _, millis ->
            // This is TTS enqueue-to-onStart, not the full M1 speech-end response budget.
            if (!isDestroyed) repository.metric("tts_enqueue_to_start", millis)
        })
        controls()
        background {
            val opened = ReportingWorkflow(IncidentStore.open(this))
            // Resume the draft chosen on the recovery prompt (05_UIUX 2.7), otherwise start a new one.
            val first = intent.getStringExtra(EXTRA_RESUME_SESSION)?.let(opened::resume) ?: opened.startDraft(draftSessionId)
            val fields = if (first.state == SessionState.REVIEWING) opened.correctableFields(first.sessionId) else emptyList()
            val types = opened.incidentTypes()
            val pack = models.load()
            ui {
                workflow = opened
                draftSessionId = first.sessionId
                manualTypes = pack?.mode != PipelineMode.FULL
                showTypes(types)
                render(first, fields)
                modelStatus.text = if (pack == null) "No trained model bundle installed. Import CfC, Laya, Whisper and Qwen before processing."
                    else "Bundle verified: ${pack.versions().entries.joinToString { "${it.key}: ${it.value}" }}"
                status.text = "Local setup checked."
            }
        }
    }

    /** Runs one workflow action on the io lane, then renders the resulting step. */
    private fun act(action: (ReportingWorkflow) -> WorkflowStep) {
        val current = workflow ?: run { status.text = "The incident store is still opening."; return }
        background {
            val next = action(current)
            val fields = if (next.state == SessionState.REVIEWING) current.correctableFields(next.sessionId) else emptyList()
            ui { render(next, fields) }
        }
    }

    private fun render(next: WorkflowStep, fields: List<String>) {
        val previousQuestion = step?.question.takeIf { step?.sessionId == next.sessionId }
        step = next
        if (next.revision != revision) {
            // A correction advanced the draft: discard in-flight model work for the old revision.
            revision = next.revision
            engine?.updateDraft(revision, next.question)
        } else engine?.currentQuestion = next.question
        question.text = next.question ?: when (next.state) {
            SessionState.LISTENING -> "Tell me what happened."
            SessionState.REVIEWING -> "Please review the details below."
            SessionState.SUBMITTED -> "Report saved."
            SessionState.CANCELLED -> "Report cancelled."
            else -> "Go on, I'm listening."
        }
        if (next.question != null && next.question != previousQuestion) speakQuestion(next.question)
        skipClarifying.visibility = if (next.state == SessionState.CLARIFYING) View.VISIBLE else View.GONE
        typePanel.visibility = if (manualTypes && next.state == SessionState.CLASSIFYING) View.VISIBLE else View.GONE

        reviewPanel.visibility = if (next.state == SessionState.REVIEWING) View.VISIBLE else View.GONE
        summary.text = next.summary.entries.joinToString("\n") { (name, entry) ->
            val shown = entry.value ?: when (entry.status) {
                FieldStatus.UNKNOWN -> "not known"
                FieldStatus.AMBIGUOUS -> "unclear"
                else -> "missing"
            }
            "${name.replace('_', ' ')}: $shown" + if (entry.kind == FieldKind.CLARIFYING) " (optional)" else ""
        }
        confirm.isEnabled = next.canConfirm
        submit.isEnabled = next.canSubmit
        if (fields.isNotEmpty()) correctionField.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fields)

        val finished = next.state == SessionState.SUBMITTED || next.state == SessionState.CANCELLED
        donePanel.visibility = if (finished) View.VISIBLE else View.GONE
        continueLinked.visibility = if (finished && next.pendingLinkedSessionId != null) View.VISIBLE else View.GONE
        doneMessage.text = when {
            next.state == SessionState.CANCELLED -> "This report was cancelled. No incident was created."
            next.incidentId != null -> "Saved on this device as ${next.incidentId}. It has not been sent anywhere: no delivery channel is set up yet."
            else -> "This report was already submitted."
        } + if (next.pendingLinkedSessionId != null) "\nYou also described a second incident that still needs its own report." else ""
        if (finished) stopListening()
    }

    private fun showTypes(types: List<IncidentTypeOption>) {
        typeButtons.removeAllViews()
        types.forEach { option ->
            typeButtons.addView(Button(this).apply {
                text = "${option.label} · ${option.departmentName}"; isAllCaps = false
                setOnClickListener {
                    act { current ->
                        val sessionId = draftSessionId
                        val turn = unclassifiedTurn?.takeIf { it.key.sessionId == sessionId }
                        current.chooseIncidentType(sessionId, option.incidentTypeId, turn).also { unclassifiedTurn = null }
                    }
                }
            })
        }
    }

    private fun speakQuestion(value: String) {
        if (listening && !echoCancellation) status.text = "Stop listening to hear the question; echo cancellation is unavailable."
        else speaker.speak(value)
    }

    private fun newDraft(sessionId: String) {
        val current = workflow ?: return
        background {
            val first = current.startDraft(sessionId)
            ui { switchDraft(first) }
        }
    }

    private fun switchToLinked() {
        val current = workflow ?: return
        val linked = step?.pendingLinkedSessionId ?: return
        background {
            val resumed = current.resume(linked)
            val fields = if (resumed.state == SessionState.REVIEWING) current.correctableFields(linked) else emptyList()
            ui { switchDraft(resumed, fields) }
        }
    }

    private fun switchDraft(next: WorkflowStep, fields: List<String> = emptyList()) {
        stopListening()
        draftSessionId = next.sessionId; revision = next.revision; step = null
        engine?.beginDraft(draftSessionId, revision, next.question)
        engine?.let(::bindCapture)
        captions.text = "Your transcript appears here."; output.text = "No model result yet."
        render(next, fields)
    }

    private fun load() {
        stopListening()
        engine?.close(); engine = null; capture?.close(); capture = null
        busy = true; controls(); status.text = "Verifying and loading local models…"
        background {
            val pack = models.load() ?: error("Import the trained model bundle first.")
            val loaded = AndroidConversationEngine(pack, repository,
                { key, transcript -> ui {
                    if (engine?.isCurrent(key) == true) captions.text = buildString {
                        append(if (transcript.isFinal) "Final: " else "Tentative: ")
                        append(transcript.text)
                        if (transcript.sourceText != transcript.text) append("\nOriginal (${transcript.language}): ${transcript.sourceText}")
                    }
                } },
                { result ->
                    ui { output.text = result.toJson().toString(2) }
                    val current = workflow
                    if (current != null) background {
                        val next = current.onEngineResult(result)
                        if (next?.state == SessionState.CLASSIFYING) unclassifiedTurn = result
                        val fields = if (next?.state == SessionState.REVIEWING) current.correctableFields(next.sessionId) else emptyList()
                        ui {
                            if (next == null) status.text = "Ignored a result for an older version of this report."
                            else { status.text = "Turn ${result.key.turnId} processed on this device."; render(next, fields) }
                        }
                    }
                },
                { message -> ui { status.text = message; stopListening() } },
                { ui {
                    if (!foreground) return@ui
                    if (speaker.speaking) {
                        val requestLatency = speaker.stop()
                        repository.metric("barge_in_stop_request", requestLatency)
                    }
                    status.text = "Listening…"
                } })
            runOnUiThread {
                if (isDestroyed || isFinishing) { loaded.close(); return@runOnUiThread }
                engine = loaded
                manualTypes = pack.mode != PipelineMode.FULL
                typePanel.visibility = if (manualTypes && step?.state == SessionState.CLASSIFYING) View.VISIBLE else View.GONE
                loaded.beginDraft(draftSessionId, revision, step?.question)
                bindCapture(loaded)
                busy = false; controls()
                modelStatus.text = "Models ready: CPU reference backend. Hexagon and kit performance are not validated."
                status.text = "Ready for a local conversation."
            }
        }
    }

    /** The capture announces its session id to the engine on every start, so it is rebuilt per draft. */
    private fun bindCapture(loaded: AndroidConversationEngine) {
        capture?.close()
        capture = ConversationCapture(loaded, object : ConversationCapture.Listener {
            override fun onReady(sampleRate: Int, echoCancellation: Boolean) = ui {
                this@ConversationActivity.echoCancellation = echoCancellation
                status.text = "Listening at ${sampleRate / 1000} kHz. " +
                    if (echoCancellation) "Echo cancellation enabled." else "Echo cancellation unavailable; speech playback disabled while listening."
            }
            override fun onLevel(level: Float, capturedAtNanos: Long) = ui {
                this@ConversationActivity.level.progress = (level * 100).toInt()
                repository.metric("listening_feedback", (System.nanoTime() - capturedAtNanos) / 1_000_000.0)
            }
            override fun onStopped() = ui { listening = false; level.progress = 0; controls() }
            override fun onError(message: String) = ui { status.text = message }
        }, draftSessionId)
    }
    private fun requestListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE)
        } else startListening()
    }
    private fun startListening() {
        if (!foreground || engine == null || busy) return
        speaker.stop()
        engine?.language = listOf("auto", "en", "hi")[languages.selectedItemPosition]
        engine?.updateDraft(revision, step?.question)
        listening = true; echoCancellation = false; controls()
        capture?.start()
    }
    private fun stopListening() { capture?.stop(); level.progress = 0; controls() }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == MICROPHONE) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else status.text = "Microphone permission denied. Typed answers still work."
        }
    }
    @Deprecated("Activity result API retained to keep the native Activity scaffold dependency-free")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (code == IMPORT_MODELS) {
            stopListening(); engine?.close(); engine = null; capture?.close(); capture = null
            busy = true; controls(); status.text = "Importing and checking model checksums…"
            background {
                val pack = models.import(uri)
                ui { busy = false; controls(); modelStatus.text = "Verified ${pack.versions().keys.joinToString()} bundle."
                    status.text = "Bundle imported. Tap Load local models." }
            }
        } else if (code == EXPORT_DIAGNOSTICS) {
            val content = exportPending ?: return
            background {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Cannot write diagnostics file.")
                ui { status.text = "Diagnostics exported. Contains local transcript evidence." }
            }
        }
    }
    private fun controls() {
        if (!::listen.isInitialized) return
        listen.isEnabled = engine != null && !busy
        listen.text = if (listening) "Stop conversation" else "Start conversation"
        process.isEnabled = engine != null && !busy && !listening
        importModels.isEnabled = !busy && !listening
        loadModels.isEnabled = !busy && !listening
    }
    private fun background(action: () -> Unit) {
        io.execute { try { action() } catch (error: Exception) {
            ui { busy = false; controls(); status.text = error.message ?: "Local action failed." }
        } }
    }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed && !isFinishing) action() } }
    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() {
        foreground = false; stopListening(); engine?.cancel(); speaker.stop()
        super.onStop()
    }
    override fun onDestroy() {
        capture?.close(); engine?.close(); speaker.close()
        io.execute { repository.close() }; io.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        /** Session id of an unfinished draft to resume instead of starting a new one. */
        const val EXTRA_RESUME_SESSION = "org.seedhecode.incidentassistant.RESUME_SESSION"
        private const val IMPORT_MODELS = 201; private const val EXPORT_DIAGNOSTICS = 202; private const val MICROPHONE = 203
    }
}
