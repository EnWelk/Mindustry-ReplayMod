package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Path;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType.DisposeEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.Trigger;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;

public class ReplayMod extends Mod {
    private static final String SETTINGS_PREFIX = "replaymod-";
    static final KeyBind openReplayKey = KeyBind.add(
        "replaymod-open", KeyCode.f9, "replaymod"
    );
    private static float replayVersion;

    private final ReplayController controller = new ReplayController();
    private final ReplayHud replayHud = new ReplayHud(this, controller);
    private IntroDialog introDialog;
    static public float ofRight;
    static public float ofLeft;
    static public float ofTop;

    @Override
    public void init() {
        loadMetadataSettings();
        loadConfiguration();
        replayHud.load();
        Events.run(Trigger.update, replayHud::handleGlobalKeybind);
        Events.on(PlayEvent.class, event -> {
            try {
                controller.stopPlaybackForNewGame();
            } catch (IOException e) {
                Log.err("Failed to stop replay before starting a new game", e);
            }
            showIntro();
        });

        Events.run(Trigger.beforeGameUpdate, controller::applyPlaybackRandomState);
        Events.run(Trigger.beforeGameUpdate, controller::maintainPlaybackMovement);
        Events.run(Trigger.afterGameUpdate, controller::recordRandomState);
        Events.run(Trigger.beforeGameUpdate, controller::maintainPlaybackWeapons);
        Events.on(DisposeEvent.class, event -> {
            if (!controller.isActive()) return;
            try {
                controller.stop();
            } catch (IOException e) {
                Log.err("Failed to finish replay while disposing the game", e);
            }
        });
    }

    private void showIntro() {
        if (IntroDialog.isDismissed()) return;
        Core.app.post(() -> {
            if (Core.scene == null || Vars.state == null || !Vars.state.isGame() ||
                controller.isPlaying()) return;
            if (introDialog == null) {
                introDialog = new IntroDialog();
            }
            if (!introDialog.isShown()) {
                introDialog.show();
            }
        });
    }

    private void loadMetadataSettings() {
        LoadedMod loadedMod = Vars.mods.getMod(getClass());
        if (loadedMod == null) {
            throw new IllegalStateException("ReplayMod metadata is unavailable");
        }

        arc.files.Fi metadataFile = loadedMod.root.child("mod.hjson");
        if (!metadataFile.exists()) {
            throw new IllegalStateException("ReplayMod mod.hjson is missing");
        }

        Jval metadata = Jval.read(metadataFile.readString());
        Jval settings = metadata.get("settings");
        if (settings == null || !settings.has("repVersion")) {
            throw new IllegalStateException("ReplayMod mod.hjson is missing settings.repVersion");
        }

        float version = settings.getFloat("repVersion", Float.NaN);
        if (!Float.isFinite(version) || version <= 0f) {
            throw new IllegalStateException("ReplayMod settings.repVersion must be a positive finite number");
        }
        replayVersion = version;
    }

    private void loadConfiguration() {
        Core.settings.defaults(
            SETTINGS_PREFIX + "of-right", 0f,
            SETTINGS_PREFIX + "of-left", 0f,
            SETTINGS_PREFIX + "of-top", 0f
        );
        ofRight = Core.settings.getFloat(SETTINGS_PREFIX + "of-right");
        ofLeft = Core.settings.getFloat(SETTINGS_PREFIX + "of-left");
        ofTop = Core.settings.getFloat(SETTINGS_PREFIX + "of-top");
    }

    void saveButtonPosition(float x, float y) {
        ofLeft = x;
        ofTop = y;
        Core.settings.put(SETTINGS_PREFIX + "of-left", x);
        Core.settings.put(SETTINGS_PREFIX + "of-top", y);
    }

    public static float replayVersion() {
        return replayVersion;
    }

    public float ofRight() {
        return ofRight;
    }

    public float ofLeft() {
        return ofLeft;
    }

    public float ofTop() {
        return ofTop;
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.register("replay-record", "<name>", "Start recording to a replay file", args -> {
            try {
                controller.startRecording(replayPath(args[0]));
                Log.info("Replay recording started: @", args[0]);
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.err("Failed to start replay recording", e);
            }
        });
        handler.register("replay-stop", "Stop replay recording or playback", args -> {
            try {
                controller.stop();
                Log.info("Replay stopped");
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.err("Failed to stop replay", e);
            }
        });
        handler.register("replay-play", "<name>", "Play a replay file", args -> {
            try {
                controller.startPlayback(replayPath(args[0]));
                Log.info("Replay playback started: @", args[0]);
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.err("Failed to start replay playback", e);
            }
        });
    }

    Path replayPath(String name) throws IOException {
        if (name.isBlank() || name.equals(".") || name.equals("..") ||
            !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Replay name may contain only letters, digits, '.', '_' and '-'");
        }

        arc.files.Fi directory = replayDirectory();
        directory.mkdirs();
        return directory.child(name + ".rep").file().toPath();
    }

    arc.files.Fi replayDirectory() {
        return Vars.dataDirectory.child("replays");
    }
}


