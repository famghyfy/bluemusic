package eu.darken.bluemusic.main.core.service;

import eu.darken.bluemusic.bluetooth.core.SourceDevice;
import eu.darken.bluemusic.main.core.database.ManagedDevice;
import eu.darken.bluemusic.settings.core.Settings;
import timber.log.Timber;

public abstract class ActionModule {

    public boolean areRequirementsMet() {
        return true;
    }

    public abstract void handle(ManagedDevice device, SourceDevice.Event event);

    /**
     * When should this module run, lower = earlier, higher = later.
     * Modules with the same priority run in parallel
     */
    public int getPriority() {
        // Default
        return 10;
    }

    protected void waitAdjustmentDelay(ManagedDevice device) {
        try {
            Long reactionDelay = device.getActionDelay();
            if (reactionDelay == null) reactionDelay = Settings.DEFAULT_REACTION_DELAY;

            Timber.tag(getClass().getSimpleName()).d("Delaying adjustment by %s ms.", reactionDelay);
            Thread.sleep(reactionDelay);
        } catch (InterruptedException e) { Timber.e(e, null); }
    }
}
