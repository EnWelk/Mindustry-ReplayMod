# ReplayMod
A utility mod for recording and replaying Mindustry games.

## Compatibility
- Replay file format: `.rep`.
- Backward compatibility is not maintained. Starting with v0.3.5, replays created with older
  versions are no longer supported.

## Building
For manual builds, replace `mindustryDir` and `arcDir` with the actual paths to your Mindustry
and Arc directories.

## TODO
- Further reduce rewind hitches for replays longer than the in-memory snapshot coverage.
- Support recording and replaying complete multiplayer sessions, including actions from
    every player and server-authoritative world changes.
- Implement position correction by recording positions on stop.

## Changelog
### v0.4.1
- Fix the game stopping or freezing when the world changes during or after playback.
  Playback is now released on every world reset — exiting to the menu, opening the map editor,
  loading a save, continuing a campaign sector, and joining a server — instead of only when a
  `PlayEvent` fires. Previously, those paths left the viewer input handler, the replay time
  scale, and the disabled Next Wave keybind installed in the new world, so the game appeared
  frozen or hijacked after the switch. World resets triggered by the replay itself (starting
  a replay, restarting it, or restoring a snapshot) are recognized and ignored.
- Add in-memory map-state snapshots during playback to make rewinding fast.
  - Capture a fine snapshot every 5 seconds of replay time, keeping the 20 most recent ones
    (about the last 100 seconds).
  - Capture a coarse snapshot every 60 seconds and retain it while the memory budget allows
    (up to 1/3 of the max heap, capped at 768 MB, with a free-memory headroom check; snapshots
    are disabled automatically for the session if the JVM runs out of memory).
  - Store each snapshot as a compressed in-memory save plus the replay-file cursor, tick, RNG
    state, and viewer mode, so playback resumes from it exactly.
- Reduce freezes during backward seeks.
  - Restore the nearest snapshot and simulate only the short remaining distance, instead of
    reloading the embedded map and re-simulating from tick 0.
  - Run seek simulation in a time-budgeted multi-tick loop (8 ms per frame) to keep the UI
    responsive during long seeks.
  - Reuse the timeline scanned at startup when restarting playback instead of re-scanning the
    whole `.rep` file.
- Finish an active recording safely whenever the world is reset (`ResetEvent` is fired before
  the tick counter resets, so the replay still ends with a valid, non-decreasing end tick).
- Add localizations: German, Spanish, French, Brazilian Portuguese, Ukrainian, Simplified
  Chinese, Traditional Chinese, Japanese, Korean, Polish, Turkish, Italian, and Indonesian
  (13 new languages on top of English, Russian, and Vietnamese).

### v0.4
- **Critical:** Fix Android compatibility.
- **Critical:** Fix random events.
- Fix the mobile UI.
- Improve core logic.
  - Scale playback using `Time.delta` instead of repeated logic updates.
  - Guard NIO reads and writes against zero-progress loops.
  - Add a global replay keybind and an error dialog.
  - Restore the Next Wave keybind whenever playback stops.
  - Remove redundant `maintainPlaybackWeapons` calls.
- Optimize the `.rep` format.
  - Pack movement and flags into a single integer.
  - Delta-encode ticks and aim coordinates.
  - Use variable-length integers for ticks, payload sizes, and action values.
  - Quantize movement and pack flags.
  - Remove the payload size from tick records.
  - Add `SINGLE_ACTION_RECORD` for ticks containing a single action.
- Fix a resource leak in `ReplayController.restartPlayback()`.
- Improve Android recording.
- Remove camera parameters.
- Fix the Next Wave button.
- Add Vietnamese localization.
- Add keybinds.
- Improve the UI.

### v0.3.4
- Fix some minor bugs.

### v0.3.3.1
- **Critical:** Fix replay stopping.

### v0.3.3
- Add tests.
- Fix the timeline.

### v0.3.2
- Fix the timeline.
- Fix the +10 seconds button.
- Show a dialog after stopping playback.
- Add a mod list to replay files and display a compatibility warning before playback.
- Add compatibility with JSON-based mods and simple JS/Java mods.

### v0.3.1
- Update the UI.
- Fix the timeline.
- Fix replay pause bugs.
- Fix replay timeline bugs.

### v0.3
- Lock the Next Wave button during playback.
- Upgrade the replay controls.
- Add replay controls for speeding up, pausing, and seeking.
- Show a screen when replay playback ends.
- Update the UI.

### v0.2.1
- **Critical:** Fix Android compatibility.

### v0.2
- Add the replay class.

### v0.1
- Implement the basic `.rep` writer.
- Implement the basic `.rep` reader.
- Implement basic recording logic.
- Implement basic replay logic.
- Implement the basic UI.