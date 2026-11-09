// SPDX-License-Identifier: Apache-2.0
package dev.likemike.kiosk.stargate;

import java.io.IOException;

/** Owns only the temporary zero; Android continues to own normal brightness. */
final class BacklightController {
    interface Panel {
        int read() throws IOException;
        void write(int value) throws IOException;
    }
    private final Panel panel;
    private int saved;
    private boolean owned;
    BacklightController(Panel panel) throws IOException {
        this.panel = panel;
        saved = panel.read();
        if (saved <= 0) throw new IOException("Enable the plugin while the backlight is lit");
    }
    void update(boolean blank, boolean awake) throws IOException {
        int current = panel.read();
        if (blank) {
            // Preserve the last awake sample: KS may already have applied its minimum.
            if (current != 0) panel.write(0);
            owned = true;
        } else {
            if (owned && current == 0) panel.write(saved);
            owned = false;
            if (awake) {
                int actual = panel.read();
                if (actual > 0) saved = actual;
            }
        }
    }
    void restore() throws IOException { update(false, false); }
}
