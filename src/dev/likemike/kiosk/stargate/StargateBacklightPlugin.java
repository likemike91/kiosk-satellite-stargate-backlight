// SPDX-License-Identifier: Apache-2.0
package dev.likemike.kiosk.stargate;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** Independent SDK plugin. No KS internals, root commands or network access. */
public final class StargateBacklightPlugin implements KioskPlugin {
    static final String PATH = "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness";
    private PluginHost host;
    private ScheduledExecutorService worker;
    private BacklightController controller;
    private boolean running, active, screenOn = true;
    private String view;
    private Double panelLevel;
    private int saverRevision, screenRevision, brightnessRevision;
    private String lastStatus;
    private final BacklightController.Panel testPanel;
    private final boolean schedule;

    public StargateBacklightPlugin() { this(null, true); }
    StargateBacklightPlugin(BacklightController.Panel panel, boolean schedule) {
        this.testPanel = panel;
        this.schedule = schedule;
    }

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        running = true;
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.view");
        host.subscribe("screen.state");
        host.subscribe("screen.brightness");
        final int saver = saverRevision, screen = screenRevision, brightness = brightnessRevision;
        read("isScreensaverActive", Collections.emptyMap(), data -> {
            if (saver == saverRevision) active = Boolean.TRUE.equals(data);
        });
        read("isScreenOn", Collections.emptyMap(), data -> {
            if (screen == screenRevision) screenOn = Boolean.TRUE.equals(data);
        });
        read("getBrightness", Collections.singletonMap("panel", true), data -> {
            if (brightness == brightnessRevision && data instanceof Number)
                panelLevel = ((Number) data).doubleValue();
        });
        read("getDeviceInfo", Collections.emptyMap(), this::initialize);
    }

    private interface Result { void accept(Object data) throws IOException; }
    private void read(String command, Map<String, Object> arguments, Result result) {
        host.executeCommand(command, arguments, (ok, data, error) -> {
            synchronized (StargateBacklightPlugin.this) {
                if (!running) return;
                if (!ok) { fail(command + " failed: " + error); return; }
                try { result.accept(data); } catch (IOException | RuntimeException e) { fail(e.getMessage()); }
            }
        });
    }

    private void initialize(Object data) throws IOException {
        if (!(data instanceof Map)) throw new IOException("No device identity available");
        Map<?, ?> device = (Map<?, ?>) data;
        if (!supported(device)) throw new IOException("Unsupported device: this plugin supports Stargate only");
        File file = new File(PATH);
        if (testPanel == null && (!file.isFile() || !file.canRead() || !file.canWrite()))
            throw new IOException("Stargate backlight is not readable and writable; nothing changed");
        if (testPanel == null) {
            File maximum = new File(file.getParentFile(), "max_brightness");
            if (new SysfsPanel(maximum).read() != 255)
                throw new IOException("Unsupported backlight maximum; expected 255");
        }
        controller = new BacklightController(testPanel == null ? new SysfsPanel(file) : testPanel);
        if (!schedule) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "stargate-backlight");
            thread.setDaemon(true);
            return thread;
        });
        // Catch delayed Android writes while dark; sample awake brightness for restoration.
        worker.scheduleWithFixedDelay(this::tick, 0, 200, TimeUnit.MILLISECONDS);
        report("Ready; select Black screensaver and disable actual screen power-off", false);
    }

    private synchronized void tick() {
        if (!running || controller == null) return;
        try {
            boolean blank = shouldBlank(active, view, screenOn, panelLevel);
            controller.update(blank, !active && view == null && screenOn);
            report(blank ? "Backlight off; Android remains awake" : "Ready; backlight controlled by Kiosk", false);
        } catch (IOException | RuntimeException e) { fail(e.getMessage()); }
    }

    static boolean shouldBlank(boolean active, String view, boolean screenOn, Double panel) {
        // A positive panel level is KS lifting brightness for voice/alarms/notifications.
        // Require an explicit zero, never an unknown value. Other saver modes stay untouched.
        return active && "black".equals(view) && screenOn && panel != null && panel == 0.0;
    }

    static boolean supported(Map<?, ?> device) {
        String model = normalized(device.get("model"));
        String sku = "sawd-0a1xx10eu1";
        return model.equals("shelly stargate") || model.equals("stargate") || model.equals(sku)
            || normalized(device.get("device")).equals(sku);
    }
    private static String normalized(Object value) {
        return value instanceof String ? ((String) value).trim().toLowerCase(Locale.ROOT) : "";
    }

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (!running) return;
        switch (event) {
            case "ks.screensaver.state":
                saverRevision++;
                active = Boolean.TRUE.equals(payload.get("active"));
                if (!active) view = null;
                break;
            case "ks.screensaver.view":
                view = payload.get("view") instanceof String ? (String) payload.get("view") : null;
                break;
            case "ks.screen.state":
                screenRevision++;
                screenOn = Boolean.TRUE.equals(payload.get("on"));
                break;
            case "ks.screen.brightness":
                brightnessRevision++;
                Object panel = payload.get("panel");
                panelLevel = panel instanceof Number ? ((Number) panel).doubleValue() : null;
                break;
            default: return;
        }
        tick();
    }

    @Override public void configure(Map<String, Object> settings) {}
    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if (!"restore".equals(command)) throw new IllegalArgumentException("Unknown command: " + command);
        // Disable enforcement until the next saver session, so recovery is not immediately undone.
        active = false;
        view = null;
        try { if (controller != null) controller.restore(); }
        catch (IOException e) { fail(e.getMessage()); }
    }

    private void fail(String message) {
        running = false;
        if (worker != null) worker.shutdownNow();
        try { if (controller != null) controller.restore(); }
        catch (IOException e) { message += "; restore failed: " + e.getMessage(); }
        report("Stopped: " + message, true);
    }
    private void report(String message, boolean error) {
        String key = error + ":" + message;
        if (!key.equals(lastStatus)) { lastStatus = key; host.status(message, error); }
    }
    @Override public synchronized void stop() throws IOException {
        running = false;
        if (worker != null) worker.shutdownNow();
        // Host API is already revoked here. Direct restoration remains available.
        if (controller != null) controller.restore();
    }

    private static final class SysfsPanel implements BacklightController.Panel {
        private final File file;
        SysfsPanel(File file) { this.file = file; }
        public int read() throws IOException {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line = reader.readLine();
                int value;
                try { value = Integer.parseInt(line == null ? "" : line.trim()); }
                catch (NumberFormatException e) { throw new IOException("Invalid backlight value", e); }
                if (value < 0 || value > 255) throw new IOException("Unexpected backlight range");
                return value;
            }
        }
        public void write(int value) throws IOException {
            if (value < 0 || value > 255) throw new IOException("Invalid brightness target");
            try (FileWriter writer = new FileWriter(file)) { writer.write(Integer.toString(value)); }
        }
    }
}
