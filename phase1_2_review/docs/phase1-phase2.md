# Phase 1-2 implementation and device handoff

The app now contains a local conversation engine in addition to the WAV recorder.
Open **Incident conversation** from the main screen. Import a ZIP containing your
trained/versioned model pack, load it, then speak or submit English text. Pause to
end a turn. The screen displays tentative/final English transcripts, original
speech transcripts, CfC decisions, Laya probabilities, evidence-backed extraction
and logged local calls. Results are proposals; this screen does not submit reports.

## Implemented

- Plain Kotlin turn contract and revision gate; final tool execution and result
  publication are rejected after session, turn or draft revision changes.
- Separate bounded audio, ASR, CfC and semantic workers. Tentative transcripts may
  drive preview relevance/extraction; preview calls and fields are not committed.
  New previews are dropped while inference is busy; final queue overload cancels
  the turn and reports an error instead of silently dropping evidence.
- Streaming PCM resampling from the microphone's reported rate to 16 kHz.
- Energy/pause endpointing with 200 ms minimum voiced audio and 800 ms silence.
  A 20-second audio window bounds memory and emits a segment without ending the
  conversational turn. Repeated Whisper window inference supplies tentative
  captions; this is a streaming wrapper, not native streaming Whisper inference.
- Native multilingual Whisper transcription plus English translation, and local
  Qwen3 GGUF extraction/tool requests through pinned whisper.cpp/llama.cpp sources.
- ONNX CfC/Laya adapters for the explicit export ABI described below, with
  vocabulary/label checks, score validation and no silent text truncation.
- Three typed, allowlisted SQLite fixture tools: `get_form_fields`,
  `get_zone_details`, `get_zone_incident_history`. The model chooses calls;
  validation failures and unresolved zones are logged and returned explicitly.
  Three tool rounds and eight calls are the hard per-turn limits.
- Exact English evidence substring checking. Output field offsets refer to the
  complete English transcript, including speech that was filtered out.
- Atomic model bundle import with streaming SHA-256 verification, traversal
  prevention, size/file-count limits and previous-pack recovery on activation
  failure. Models are held in app-private storage and are never downloaded by
  the app. Android cannot independently prove a model's claimed training family;
  the reviewed manifest and hashes identify the supplied artifact.
- Local diagnostics export containing real output, calls, stage timing samples,
  device/OS/RAM identity, process memory and battery/thermal snapshots. Metrics
  retain the latest 10,000 samples. These are not measured power consumption.
- Android offline English TTS reference adapter. Only a voice that declares no
  network requirement is selected. Hardware AEC is attached to conversation
  capture where available; playback during capture is disabled without it.
  User speech requests playback cancellation. Acoustic stop time still needs
  measurement on the device.

## Build

Android 7.0/API 24 or newer is now required by the ONNX Runtime dependency. Java
17, SDK 34 and the existing Gradle/AGP versions remain the build baseline.

For Android Studio, open the repository root and use Java 17 for the Gradle JDK.
Run `scripts/prepare_native.ps1` once from the terminal, then add
`nativeModels=true` to the root `gradle.properties` before syncing and running
the app. Install NDK 27.2.12479018 and CMake 3.22.1 through SDK Manager. Use an
ARM64 device for the native model pipeline. Android builds and device testing
are handed over to you in Android Studio; no further Android checks are being
run here.

```powershell
powershell -File scripts/prepare_native.ps1
.\gradlew.bat :app:assembleDebug -PnativeModels=true
```

The native build uses NDK 27.2.12479018 and CMake 3.22.1. Install them through SDK
Manager, or let Gradle install them if their SDK licences are already accepted.
Native builds target `arm64-v8a`; both engines are built with CPU execution and
optimized native code even in the debug APK. Native sources remain outside git
under `third_party/`; the preparation script pins the tested upstream revisions.
Each engine links its own static GGML with hidden library symbols to avoid
cross-engine symbol conflicts.

Without `-PnativeModels=true`, the app still builds its recording/model-import
screen and ONNX adapters, but will explicitly reject loading the full pipeline
because the Whisper/Qwen native runtimes are absent. Such an APK is not the model
demo. The native-enabled debug output is `app/build/outputs/apk/debug/app-debug.apk`.
No model weights are included in either APK.

```powershell
.\gradlew.bat :core:inference:test :core:audio:testDebugUnitTest :app:lintDebug
```

## Provide real artifacts

Copy `scripts/model-pack.example.json` to a working spec and set real versions,
files, trained vocabulary IDs and label order. The example intentionally has
no Laya label list and placeholder versions, so it cannot be mistaken for a
ready pack. Prepare these artifacts:

| Role | Supported implementation | Required artifact |
| --- | --- | --- |
| ASR | CPU whisper.cpp, multilingual | multilingual Base/Small GGML `.bin` |
| Relevance | CPU ONNX, trained CfC wrapper | INT8 graph + exact training vocabulary |
| Incident type | CPU ONNX, trained Laya wrapper | INT8 graph + exact training vocabulary + trained label order |
| Extraction/calls | CPU llama.cpp, Qwen3/no-think ChatML | compatible Qwen3 GGUF |
| Speech output | Android offline English voice | preinstalled local voice, not a bundled Melo/Kokoro model |

```powershell
python scripts/make_model_bundle.py --spec PATH_TO_SPEC.json --artifacts PATH_TO_WEIGHTS --output incident-models.zip
```

On the device, select **Import local model bundle**, then **Load local models**.
Missing runtime, model, checksum, vocabulary, label or incompatible tensor names
produces an explicit error. No heuristic classifier, fake transcript, canned
response or network fallback substitutes for missing models.

### CfC/Laya export ABI: `word_ids_mask_v1`

This is an implemented adapter ABI, not a claim about your existing trained
models. If those models use embeddings, BPE/WordPiece, additional recurrent
state, or different tensor shapes, export a matching wrapper or add a matching
adapter before importing them. Do not relabel an incompatible graph.

- Normalize using lowercase with a fixed locale. Tokenize runs of Unicode letters,
  digits and underscore, with each remaining non-whitespace character a token.
- Vocabulary JSON maps normalized tokens to their actual training IDs. Pad with
  `padding_id`, use `unknown_id` for unseen tokens; no beginning/end tokens are
  added. Inputs are INT64 `input_ids` and `attention_mask`, shape `[1,max_tokens]`.
- CfC context format is `question: <question> answer: <sentence>` when the app has
  an approved question; otherwise it is just the sentence. The trained wrapper
  must include the CfC unroll/state handling used for sentence inference. Reset
  in this adapter is stateless because that unroll lives inside the exported graph.
- Output `scores` is FLOAT with one entry per manifest label. It is either logits
  normalized with softmax or probabilities whose sum is checked. CfC labels are
  exactly `filler` and `relevant`. INT8 weights may coexist with FLOAT outputs.
- Laya labels contain exactly the 28 packaged taxonomy IDs plus `undetermined`
  and `none`, in the graph's actual index order. The top two real incident labels
  are proposed when uncertain; `<0.6` sets `needs_confirmation`. The app retains
  the original `confidence` key and adds probabilities/outcome under metadata.
- Inputs exceeding the trained token length fail explicitly. Export appropriate
  context capacity and test long spoken turns; no silent truncation is performed.
- A manifest's `quantization: int8` declaration is not an independent graph audit.
  Validate actual INT8 export and pre/post-quantization accuracy during Phase 1.

## Phase 1 tooling and honest completion status

```powershell
python scripts/phase1_preflight.py --output tmp/phase1/device-preflight.json
python scripts/audit_conversations.py DATASET.jsonl --require-m1-counts
```

Preflight requires a connected, authorized Android device. It records the actual
chipset/OS/ABI/memory plus battery/thermal snapshots and optional local QNN HTP
files. It never marks acceleration or performance verified from file presence.

The dataset audit checks annotated sentence/field evidence, split/ID integrity,
exact normalized sentence overlap, label coverage and optional M1 counts. Its
JSON report explicitly leaves filler/style/location shortcuts, look-alike coverage,
independent spoken data and INT8 accuracy pending. It does not generate or train
the M1 dataset or infer that it exists from the PDF's reported counts.

Phase 1 and Phase 2 are not fully accepted until real trained artifacts and the
physical Snapdragon kit are available. Still required:

1. Obtain/review CfC/Laya weights, preprocessing and trained label maps. Adapt
   the actual model ABI if it differs from the wrapper implemented here.
2. Benchmark the shortlisted models on SM8750P and confirm installed QAIRT/SNPE
   compatibility. This implementation does not execute models on Hexagon/QNN;
   add the SDK-specific backend and demonstrate real NPU execution before
   claiming M1's acceleration requirement or reported CfC numbers.
3. Select and integrate the actual MeloTTS/Kokoro export if required for the final
   M1 stack. Android local TTS is the app testing/reference path.
4. Validate Hindi/Hinglish names, times, IDs and English-question comprehension
   on independent human recordings. Both transcripts are retained, but word-level
   source-to-English alignment is not implemented; current provenance is at whole
   transcript/English span level.
5. Validate AEC quality, noisy-speech endpointing, acoustic barge-in stop time,
   response-start P95, sustained memory/power/thermal behaviour and offline proof.
   Empty metrics remain null and never appear as a pass. `barge_in_stop_request`
   measures the stop API call; it is not the <150 ms acoustic result.
6. Record speech-end-to-first-question-audio timings with the real Phase 3-4
   question selector. The standalone screen only measures raw inference and
   TTS enqueue-to-start; cached/dynamic question target samples remain absent.

## Merge with the existing Phase 3-4 implementation

`AndroidConversationEngine` is the composition point. Its `onResult(TurnResult)`
callback supplies proposals; `TurnResult.toJson()` keeps the existing process-turn
keys and adds contract/version, evidence and classification metadata. Bridge the
actual friend's consumer to that callback after comparing their fixtures.

- Pass the app's approved follow-up and current revision through `updateDraft`.
  Advance revision before a correction so pending calls/results are invalidated.
  Select a new app-owned session with `beginDraft`; pass the same session ID to
  `ConversationCapture` so audio restarts and text input retain draft identity.
- Read tentative transcripts from `onTranscript`; only the final `onResult`
  enters persistent app field state. UI callbacks should also check `isCurrent`.
- Replace `LocalTools` with the existing app repository at composition time.
  This Phase 2 DB has fixture zones/history and engine logs, not full drafts,
  submissions, permissions or notifications. Fixture locations explicitly need
  confirmation; a room name alone never silently fills a building/floor.
- Use the actual trained Laya/taxonomy IDs; explicit `none/undetermined` metadata
  is not a new database incident-type ID. `incident_groups` are grounded spans,
  not finalized draft identities. Confirm two-incident splitting with real tests.
- The three M1 tools are implemented. Older
  `get_incident_type_candidates`/`classify_priority` signatures require an
  explicit adapter to Laya/app rules if your friend's consumer calls them.
  Unknown tool names are logged errors. Never map Low/Medium/High silently to
  P1-P4 or replace the friend's deterministic priority logic with model guesses.
- Import the existing form/review/submission tests into the Android scaffold.
  Keep model proposals separate from trusted field state; priority/routing,
  confirmation and idempotent local submission remain the app layer's job.

Upstream API references:
[Whisper](https://github.com/ggml-org/whisper.cpp),
[llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md),
[ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html),
[Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler).
