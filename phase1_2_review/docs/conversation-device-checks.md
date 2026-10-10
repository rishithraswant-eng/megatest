# Conversation app checks

Run these on a real Android 7.0+ ARM64 phone with the native-enabled APK and
reviewed model pack. No physical phone or kit was connected during implementation;
this is a checklist, not a report of completed physical tests. An isolated API 33
emulator was used to check screen startup and the missing-model error path.

1. Open Incident conversation with no weights. Verify import/setup remains
   available and processing stays disabled. Try an invalid checksum/label-map
   bundle; the app must report an error and preserve the installed pack.
2. Import the real model pack, load it, then process the water-leak example in
   English text. Inspect actual Laya probabilities, CfC decisions, matching
   English evidence spans and any genuine model-selected local tool calls.
3. Deny microphone permission and verify English text still works. Grant it,
   speak, pause naturally, and verify final output is produced without Stop.
   Continue speaking for more than 20 seconds; window flushing must not by itself
   end the conversational turn.
4. Repeat in Hindi/Hinglish with office noise. Check source and English
   transcripts for room/floor/time/equipment identifiers, plus unknown answers,
   negations, corrections and filler. Any hazard dropped by CfC is a failure.
5. Supply an approved English follow-up and use local playback. Without AEC,
   playback while listening must be rejected. With AEC, interrupt playback and
   measure acoustic stop time independently of the recorded API request time.
   The device's own question must not be transcribed as a user answer.
6. Trigger processing, then advance the draft revision or start another turn.
   Old results/tool requests must not publish into the new revision. Replay the
   friend's real Phase 3-4 correction cases after connecting their consumer.
7. Stop/back out, rotate, background and reopen during capture/model loading.
   Microphone and speech playback must stop in the background. A new Activity
   must not receive the old one's results. Processing errors must leave a usable
   screen and never commit fabricated fields.
8. Export diagnostics after several back-to-back reports. Check actual stage
   samples and device identity. Missing P95 target measurements remain null.
   Distinguish raw extraction timing, enqueue-to-TTS start, and true speech-end
   response latency. Battery temperature is not power draw or a thermal benchmark.
9. Disable the network before the run. Verify imported weights, local tools,
   source conversion, extraction and offline TTS still run. Separately verify
   selected models on the physical Snapdragon kit using its actual accelerator;
   a successful CPU phone run is not the Hexagon acceptance test.

After the Phase 3-4 merge, repeat safety-first missing-field questions, explicit
unknown answers, uncertain/two-incident outcomes, review corrections, all four
review actions, idempotent submission and consistent queue/map updates. This
standalone Phase 2 screen emits proposals and never claims to submit a report.
