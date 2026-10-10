# Physical Android smoke test

- Fresh install: grant microphone permission and record a spoken sentence.
- Confirm level changes, timer increments and Stop and save produces one clip.
- Replay: verify understandable audio and the displayed duration.
- Deny permission: verify explanatory status and that no capture begins.
- Permanently deny permission: use the settings link, grant, and retry.
- Airplane mode: record and replay without network access.
- Tap repeatedly: ensure one active capture and no duplicate completion.
- During recording, rotate the phone or switch apps: capture stops; return and
  verify the completed clip appears. No microphone indicator should remain.
- Record for two minutes: verify automatic completion and microphone release.
- Restart the app: verify saved clips remain playable.
- Delete: cancel first, then confirm; verify only that selected clip disappears.
- Increase font size: ensure content scrolls and buttons remain reachable.

These checks require the actual phone. A successful build does not prove that
permissions, microphone routing or playback work on that device.
