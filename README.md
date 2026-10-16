# ReplayMod
Utility mod for recording and replaying Mindustry games.

TODO:
- Fix some bag in map after replay
- Lock UI button "Next wawe"
- Add screen when replay ends
- Auto replay name
- New UI
- Record and replay complete multiplayer sessions, including actions from every player and server-authoritative world changes.

## Android builds

Android loads Java mods from a root-level `classes.dex`, not directly from `.class` files. `gradle jar` generates and packages this DEX alongside the desktop classes and root `mod.hjson`; it requires an installed Android SDK. Set `androidSdkDir` or `ANDROID_HOME` if the SDK is not in its standard location.