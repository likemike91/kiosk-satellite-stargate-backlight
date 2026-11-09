// SPDX-License-Identifier: Apache-2.0
package dev.likemike.kiosk.stargate;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

public final class BacklightTest {
    static final class Panel implements BacklightController.Panel {
        int value = 80, writes;
        boolean fail;
        public int read() throws IOException { if (fail) throw new IOException("disconnected"); return value; }
        public void write(int n) throws IOException { if (fail) throw new IOException("disconnected"); value = n; writes++; }
    }
    static Map<String, Object> map(String key, Object value) {
        return Collections.singletonMap(key, value);
    }
    static final class Host {
        final Map<String, PluginHost.CommandCallback> callbacks = new HashMap<>();
        String status;
        boolean error;
        PluginHost proxy = (PluginHost) Proxy.newProxyInstance(PluginHost.class.getClassLoader(),
            new Class<?>[] {PluginHost.class}, (p, method, args) -> {
                switch (method.getName()) {
                    case "executeCommand": callbacks.put((String) args[0], (PluginHost.CommandCallback) args[2]); return null;
                    case "status": status = (String) args[0]; error = (Boolean) args[1]; return null;
                    case "subscribe": return null;
                    case "toString": return "TestHost";
                    default: throw new AssertionError("Unexpected host call: " + method.getName());
                }
            });
        void reply(String command, Object data) { callbacks.remove(command).onResult(true, data, null); }
        void ready() {
            reply("isScreensaverActive", false);
            reply("isScreenOn", true);
            reply("getBrightness", 0.4);
            reply("getDeviceInfo", map("device", "Stargate"));
        }
    }
    static void event(StargateBacklightPlugin p, String event, String key, Object value) {
        p.onEvent("ks." + event, map(key, value));
    }
    static void black(StargateBacklightPlugin p) {
        event(p, "screensaver.view", "view", "black");
        event(p, "screen.brightness", "panel", 0.0);
        event(p, "screensaver.state", "active", true);
    }
    public static void main(String[] args) throws Exception {
        assert StargateBacklightPlugin.supported(map("model", " Shelly Stargate "));
        assert StargateBacklightPlugin.supported(map("model", "STARGATE"));
        assert StargateBacklightPlugin.supported(map("device", "SAWD-0A1XX10EU1"));
        assert !StargateBacklightPlugin.supported(map("name", "Shelly Stargate"));
        assert !StargateBacklightPlugin.supported(map("model", "Not a Stargate tablet"));
        assert !StargateBacklightPlugin.supported(map("model", "Shelly Wall Display X2i"));
        Panel panel = new Panel();
        BacklightController c = new BacklightController(panel);
        panel.value = 10; c.update(true, false);
        assert panel.value == 0;
        int writes = panel.writes; c.update(true, false); assert panel.writes == writes;
        panel.value = 10; c.update(true, false); assert panel.value == 0; // delayed Android minimum
        c.restore(); assert panel.value == 80; // saved before KS dimmed
        panel.value = 150; c.update(false, true); c.update(true, false); c.restore(); assert panel.value == 150;
        c.update(true, false); panel.value = 110; c.restore(); assert panel.value == 110; // don't overwrite OS restore
        panel.value = 0;
        try { new BacklightController(panel); throw new AssertionError("dark startup accepted"); }
        catch (IOException expected) {}

        panel = new Panel(); Host host = new Host();
        StargateBacklightPlugin p = new StargateBacklightPlugin(panel, false);
        p.start(host.proxy, Collections.emptyMap()); host.ready();
        black(p); assert panel.value == 0;
        panel.value = 140;
        event(p, "screen.brightness", "panel", 0.5); assert panel.value == 140; // notification/voice lift
        panel.value = 10;
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 0;
        event(p, "screensaver.view", "view", null); assert panel.value == 80; // wake starts before state false
        event(p, "screensaver.state", "active", false);
        event(p, "screensaver.view", "view", "clock");
        event(p, "screensaver.state", "active", true);
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 80; // clock stays lit
        black(p); p.stop(); assert panel.value == 80;
        black(p); assert panel.value == 80; // late callbacks cannot reblank after stop

        // Late initial queries must not overwrite newer screen/saver/brightness events.
        panel = new Panel(); host = new Host(); p = new StargateBacklightPlugin(panel, false);
        p.start(host.proxy, Collections.emptyMap());
        host.reply("getDeviceInfo", map("model", "Stargate"));
        black(p); assert panel.value == 0;
        host.reply("isScreensaverActive", false); host.reply("getBrightness", 0.7);
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 0;
        p.execute("restore", Collections.emptyMap()); assert panel.value == 80;
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 80;
        black(p); assert panel.value == 0;
        event(p, "screen.state", "on", false); assert panel.value == 80;
        host.reply("isScreenOn", true);
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 80;
        p.stop();

        panel = new Panel(); host = new Host(); p = new StargateBacklightPlugin(panel, false);
        p.start(host.proxy, Collections.emptyMap());
        host.reply("getDeviceInfo", map("model", "Other tablet"));
        black(p); assert panel.writes == 0 && host.error;

        panel = new Panel(); host = new Host(); p = new StargateBacklightPlugin(panel, false);
        p.start(host.proxy, Collections.emptyMap()); host.ready(); panel.fail = true;
        black(p); assert host.error; panel.fail = false;
        event(p, "screen.brightness", "panel", 0.0); assert panel.value == 80; // failure stops enforcement
        p.stop();
        System.out.println("Backlight controller and plugin lifecycle tests passed");
    }
}
