package com.enwelk.replaymod;

import arc.Core;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

/** Shows the welcome message when a world is entered. */
public class IntroDialog extends BaseDialog {
    private static final String DISMISSED_SETTING = "replaymod-intro-dismissed";

    /** Creates the welcome message and its persistent opt-out checkbox. */
    public IntroDialog() {
        super("@replaymod.intro.title");

        cont.margin(16f);
        cont.add("Intro message\\nIf you find the bag, please message me on Telegram: @EnWelk").wrap().width(420f).pad(12f);
        cont.row();
        cont.check("Dont show again",
            Core.settings.getBool(DISMISSED_SETTING, false),
            checked -> Core.settings.put(DISMISSED_SETTING, checked)
        ).left().pad(8f);
        buttons.button("Close", Icon.cancel, this::hide).size(200f, 58f);
        closeOnBack();
    }

    /** Returns whether the player opted out of future welcome messages. */
    public static boolean isDismissed() {
        return Core.settings.getBool(DISMISSED_SETTING, false);
    }
}
