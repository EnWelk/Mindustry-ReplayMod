# ReplayMod

A utility mod for recording and replaying Mindustry games.
I couldn't be bothered to maintain backward compatibility, so starting with v0.3.5, replays created with older versions are no longer supported.
For manual builds, replace `mindustryDir` and `arcDir` with the actual paths to your Mindustry and Arc directories.
Replay file format: `.rep`

## TODO
- Optimize rewind performance.
- Fix some bugs with maps after replay playback.
- Support recording and replaying complete multiplayer sessions, including actions from every player and server-authoritative world changes.

Сделать коррекцию позиции путем записи при остановке

## Changelog

### v0.4
- **Critical:** Fix Android compatibility.
- **Critical:** Fix random events.
- Fix the mobile UI.
- Improve core logic:
  - Scale playback using `Time.delta` instead of repeated logic updates.
  - Guard NIO reads and writes against zero-progress loops.
  - Add a global replay keybind and an error dialog.
  - Restore the Next Wave keybind whenever playback stops.
  - Remove redundant `maintainPlaybackWeapons` calls.
- Optimize the `.rep` format:
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
- Add keybinds
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