package ai.jackknife.killayue.studio.ui;

import android.view.View;

import ai.jackknife.killayue.studio.NativeEngine;

/** One of the Studio's screens (the Vault, Arrange, Mixer, the Splitter), swapped by the bar at the bottom. */
interface Screen {
    String name();

    View view();

    /** Shown or hidden (it keeps its state between). */
    void shown();

    void hidden();

    /** Every frame while shown: the engine's position, peaks and health (null when there is no engine). */
    void frame(NativeEngine.Status s);
}
