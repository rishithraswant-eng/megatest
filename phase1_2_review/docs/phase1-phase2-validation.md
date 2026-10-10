# Implementation validation

Validated on Windows with Java 17, Gradle 8.11.1, SDK 34, NDK 27.2.12479018 and
CMake 3.22.1. Native source revisions are pinned in `scripts/prepare_native.ps1`.

## Completed checks

- Native-enabled ARM64 debug APK built successfully, including Whisper, llama,
  ONNX Runtime and C++ runtime libraries.
- Packaged configuration contains 28 incident types and 6 departments.
- Packaged Android manifest requests RECORD_AUDIO and no INTERNET permission.
- Eleven unit tests passed: three existing WAV checks, three audio/resampling
  checks and five conversation checks. Conversation checks cover filler exclusion,
  original English evidence offsets, stale revision rejection before tool calls,
  fabricated evidence rejection and preview/final tool separation.
- Android lint passed with no errors. Warnings remain, including English strings
  in the prototype screen and the existing SDK/ARM64 compatibility advisories.
- Python tooling syntax and CLI entry points checked. Native preparation script
  verified the checked-out source commits. ADB preflight ran successfully against
  the isolated emulator; its report correctly leaves accelerator/benchmark pass
  flags false.
- Installed the native-enabled APK on an isolated Android 13/API 33 emulator.
  MainActivity opened, its conversation button launched ConversationActivity,
  and the local English TTS service initialized. With no trained bundle installed,
  voice/text inference controls remained disabled. Tapping Load displayed the
  missing-model error and kept those controls disabled. No AndroidRuntime crash
  was reported in this smoke check. The emulator was stopped afterward.

The user's existing emulator had an Android SettingsProvider boot failure before
app startup. Testing used a separate temporary AVD with the same installed system
image; the existing AVD's data was not wiped or repaired.

At the user's request, further Android builds and testing are left to Android
Studio. Automatic approval review rejected deletion of the temporary AVD data
with "blocked by policy". Those files remain under `tmp/emulator-smoke`; the
emulator is stopped.

## Not validated or completed

No trained CfC/Laya/Whisper/Qwen bundle or physical kit was supplied. Consequently,
these checks do not establish real recognition, translation, relevance accuracy,
Laya accuracy, extraction accuracy or model-selected tool-call quality. Trained
CfC/Laya exports must match the declared adapter ABI or need an added adapter.

Current model execution is a CPU reference implementation. QAIRT/Hexagon execution,
Melo/Kokoro model integration, physical AEC/barge-in behaviour, source-word alignment,
speech-end response budgets and sustained power/thermal benchmarks remain pending.
The friend's actual Phase 3-4 source/fixtures were not supplied; compatibility and
the full reporting workflow still need the real-code merge and Android tests.

See `phase1-phase2.md` for model-bundle preparation, the interface and remaining
acceptance requirements. Phase 1-2 should not be marked fully complete on hardware
from these build/unit/emulator results.
