/**
 * Copyright 2026- Justin K. Belcher, Heron Arts LLC
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.color.ColorParameter;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.MutableParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

import studio.jkb.pb.LOG;

/**
 * Represents a Pixelblaze device discovered on the local network.
 * Owns its own WebSocket connection, parameters and per-device state so
 * that the plugin can theoretically host multiple concurrent devices.
 */
public class PixelblazeDevice extends LXComponent implements PixelblazeConnection.Listener {

  private static final String NONE = "(none)";

  private static final long INITIAL_COOLDOWN_MS = 10000;
  private static final long MAX_COOLDOWN_MS = 120000;
  private static final double SEND_INTERVAL_MS = 50;
  private static final int DEFAULT_PLAYLIST_DURATION_MS = 30000;

  public interface Listener {
    public default void onStatsChanged(PixelblazeDevice device) {}
    public default void onPatternListChanged(PixelblazeDevice device) {}
    public default void onPlaylistChanged(PixelblazeDevice device) {}
    public default void onControlsChanged(PixelblazeDevice device) {}
  }

  private final List<Listener> listeners = new ArrayList<>();

  // Connection
  public final String address;
  private PixelblazeConnection connection;
  private long lastSeen;

  // Reconnect cooldown after connection failure
  private long reconnectCooldownMs = INITIAL_COOLDOWN_MS;
  private long lastConnectionFailureMs = 0;
  private boolean hadConnectionFailure = false;

  // Sending parameters to device
  private double sendAccumulator = 0;
  private final ArrayDeque<LXParameter> sendQueue = new ArrayDeque<>();

  // Pattern list
  private final List<String> patternIds = new ArrayList<>();
  private final LinkedHashMap<String, String> patterns = new LinkedHashMap<>();
  private String pendingActivePatternId = null;

  // Playlist
  private final List<String> playlistPatternIds = new ArrayList<>();
  private final Map<String, Integer> playlistDurations = new HashMap<>();

  // Pattern controls
  private final List<Control> mutableControls = new ArrayList<>();
  public final List<Control> controls = Collections.unmodifiableList(this.mutableControls);
  // Color controls, a subset of pattern controls
  private final List<ColorParameter> mutableColors = new ArrayList<>();
  public final List<ColorParameter> colors = Collections.unmodifiableList(this.mutableColors);

  private boolean isLocalChange = false;

  // Parameters

  public final BooleanParameter connected =
    new BooleanParameter("Connected", false)
      .setDescription("[Read-only] Whether this Pixelblaze is currently connected");

  public final CompoundParameter brightness =
    new CompoundParameter("Brightness", 1, 0, 1)
      .setDescription("Brightness of this Pixelblaze");

  public final DiscreteParameter patternSelector =
    new DiscreteParameter("Pattern", new String[]{NONE})
      .setDescription("Active pattern on this Pixelblaze");

  // Sequencer

  public final EnumParameter<SequencerMode> sequencerMode =
    new EnumParameter<>("Sequencer", SequencerMode.OFF)
      .setDescription("Sequencer mode on this Pixelblaze");

  public final BooleanParameter sequencerRunning =
    new BooleanParameter("Run", false)
      .setDescription("Whether the sequencer is running");

  public final CompoundParameter sequenceTimer =
    new CompoundParameter("Shuffle Time", 15, 1, 10000000)
      .setDescription("Seconds per pattern in shuffle mode")
      .setUnits(LXParameter.Units.SECONDS);

  public final MutableParameter playlistPosition =
    new MutableParameter("Position", -1)
      .setDescription("Current position in playlist");

  // Stats (read-only, populated from device config and periodic status messages)

  public final StringParameter deviceName =
    new StringParameter("Name")
      .setDescription("Name of this Pixelblaze");

  public final StringParameter deviceVersion =
    new StringParameter("Version")
      .setDescription("Firmware version of this Pixelblaze");

  public final MutableParameter devicePixelCount =
    new MutableParameter("Pixels", 0)
      .setDescription("Number of LEDs configured on this Pixelblaze");

  public final MutableParameter deviceFps =
    new MutableParameter("FPS", 0)
      .setDescription("Rendering frame rate of this Pixelblaze");

  public final MutableParameter deviceMem =
    new MutableParameter("Memory", 0)
      .setDescription("VM memory usage of this Pixelblaze");

  public final MutableParameter deviceStorageUsed =
    new MutableParameter("Storage Used", 0)
      .setDescription("Flash storage used on this Pixelblaze");

  public final MutableParameter deviceStorageSize =
    new MutableParameter("Storage Size", 0)
      .setDescription("Total flash storage on this Pixelblaze");

  public final MutableParameter deviceUptime =
    new MutableParameter("Uptime", 0)
      .setDescription("Uptime in milliseconds of this Pixelblaze");

  public PixelblazeDevice(LX lx, String address) {
    super(lx, address);
    this.address = address;

    addParameter("connected", this.connected);
    addParameter("brightness", this.brightness);
    addParameter("pattern", this.patternSelector);
    addParameter("sequencerMode", this.sequencerMode);
    addParameter("sequencerRunning", this.sequencerRunning);
    addParameter("sequenceTimer", this.sequenceTimer);
    addParameter("playlistPosition", this.playlistPosition);
    addParameter("deviceName", this.deviceName);
    addParameter("deviceVersion", this.deviceVersion);
    addParameter("devicePixelCount", this.devicePixelCount);
    addParameter("deviceFps", this.deviceFps);
    addParameter("deviceMem", this.deviceMem);
    addParameter("deviceStorageUsed", this.deviceStorageUsed);
    addParameter("deviceStorageSize", this.deviceStorageSize);
    addParameter("deviceUptime", this.deviceUptime);

    touch();
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof PixelblazeDevice that) {
      return Objects.equals(this.address, that.address);
    }
    return false;
  }

  // Keep Alive

  public PixelblazeDevice touch() {
    this.lastSeen = System.currentTimeMillis();
    return this;
  }

  public long getLastSeen() {
    return this.lastSeen;
  }

  // Connection

  public void connect() {
    if (this.connection != null) {
      return;
    }
    LOG.log("Connecting to " + getLabel());
    this.connection = new PixelblazeConnection(this.lx, this, this);
    this.connection.connect();
  }

  public void disconnect() {
    if (this.connection != null) {
      LOG.log("Disconnecting from " + getLabel());
      this.connection.disconnect();
      this.connection = null;
    }
    this.connected.setValue(false);
    this.sendQueue.clear();
    clear();
  }

  public long reconnectCooldownRemainingMs() {
    if (!this.hadConnectionFailure) {
      return 0;
    }
    long elapsed = System.currentTimeMillis() - this.lastConnectionFailureMs;
    return Math.max(0, this.reconnectCooldownMs - elapsed);
  }

  // Loop

  public void loop(double deltaMs) {
    // Keep actively connected device alive even without beacons
    if (this.connection != null && this.connection.isConnected()) {
      touch();
    }

    // Throttle sends to avoid overwhelming the device
    if (this.connection == null || this.connection.isSending()) {
      return;
    }
    if (this.sendAccumulator < SEND_INTERVAL_MS) {
      this.sendAccumulator += deltaMs;
      return;
    }
    if (this.sendQueue.isEmpty()) {
      return;
    }
    this.sendAccumulator = 0;
    LXParameter parameter = this.sendQueue.removeFirst();

    // Device controls
    if (parameter == this.brightness) {
      this.connection.sendSetBrightness(this.brightness.getValue());
    } else if (parameter == this.sequencerMode) {
      this.connection.sendSetSequencerMode(this.sequencerMode.getEnum().ordinal());
    } else if (parameter == this.sequencerRunning) {
      this.connection.sendRunSequencer(this.sequencerRunning.isOn());
    } else if (parameter == this.sequenceTimer) {
      this.connection.sendSequenceTimer((int) this.sequenceTimer.getValue());

    // Pattern controls
    } else {
      Control control = findControl(parameter);
      if (control == null) {
        LOG.error("Pixelblaze control was updated but not found in control map: " + parameter.getCanonicalLabel());
        return;
      }
      switch (control.type) {
        case ControlType.SLIDER, INPUT_NUMBER -> {
          this.connection.sendSetControl(control.name, control.parameter.getValue());
        }
        case HSV_PICKER -> {
          ColorParameter cp = (ColorParameter) control.parameter;
          double h = cp.hue.getNormalized();
          double s = cp.saturation.getNormalized();
          double v = cp.brightness.getNormalized();
          this.connection.sendSetControlHSV(control.name, h, s, v);
        }
        case RGB_PICKER -> {
          int color = ((ColorParameter) control.parameter).calcColor();
          double r = (LXColor.red(color) & 0xff) / 255.0;
          double g = (LXColor.green(color) & 0xff) / 255.0;
          double b = (LXColor.blue(color) & 0xff) / 255.0;
          this.connection.sendSetControlRGB(control.name, r, g, b);
        }
        case TOGGLE -> {
          this.connection.sendSetControl(control.name, ((BooleanParameter) control.parameter).getValueb() ? 1.0 : 0.0);
        }
        case TRIGGER -> {
          this.connection.sendSetControl(control.name, 1.0);
        }
        case SHOW_NUMBER, GAUGE -> {
          // Read-only controls, should never be queued.
        }
      }
    }
  }

  private Control findControl(LXParameter parameter) {
    for (Control control : this.controls) {
      if (control.parameter == parameter) {
        return control;
      }
      // Color parameter sub-parameters (hue/saturation/brightness) resolve to the parent entry
      if (control.parameter instanceof ColorParameter cp) {
        if (parameter == cp.hue || parameter == cp.saturation || parameter == cp.brightness) {
          return control;
        }
      }
    }
    return null;
  }

  // Parameter changes

  @Override
  public void onParameterChanged(LXParameter parameter) {
    if (this.isLocalChange) {
      return;
    }
    if (parameter == this.patternSelector) {
      selectedPatternChanged();
      return;
    }
    if (this.connection == null) {
      return;
    }
    if (parameter == this.brightness ||
        parameter == this.sequencerMode ||
        parameter == this.sequencerRunning ||
        parameter == this.sequenceTimer) {
      queueParameterChange(parameter);
      return;
    }
    // Pattern controls
    Control control = findControl(parameter);
    if (control != null && control.type.isInput) {
      queueParameterChange(control.parameter);
    }
  }

  private void queueParameterChange(LXParameter p) {
    // Dedupe everything except triggers
    if (p instanceof TriggerParameter triggerParameter) {
      if (triggerParameter.isOn()) {
        this.sendQueue.add(p);
      }
    } else if (!this.sendQueue.contains(p)) {
      this.sendQueue.add(p);
    }
  }

  // PixelblazeConnection.Listener

  @Override
  public void onConnected(PixelblazeConnection connection) {
    LOG.log("Connected to " + getLabel());
    this.connected.setValue(true);
    this.reconnectCooldownMs = INITIAL_COOLDOWN_MS;
    this.hadConnectionFailure = false;
  }

  @Override
  public void onDisconnected(PixelblazeConnection connection) {
    LOG.log("Disconnected from " + getLabel());
    if (this.connection == connection) {
      this.connection = null;
      this.connected.setValue(false);
      clear();
    }
  }

  @Override
  public void onError(PixelblazeConnection connection, Throwable error) {
    LOG.error(error, "Connection error");
    this.hadConnectionFailure = true;
    this.lastConnectionFailureMs = System.currentTimeMillis();
    this.reconnectCooldownMs = Math.min(this.reconnectCooldownMs * 2, MAX_COOLDOWN_MS);
    LOG.log("Next auto-connect to " + getLabel() + " in " + (this.reconnectCooldownMs / 1000) + "s");
  }

  @Override
  public void onConfig(PixelblazeConnection connection, String name, String version, int pixelCount, double brightness) {
    this.deviceName.setValue(name);
    this.deviceVersion.setValue(version);
    this.devicePixelCount.setValue(pixelCount);
    notifyStatsChanged();

    this.isLocalChange = true;
    this.brightness.setValue(brightness);
    this.isLocalChange = false;
  }

  @Override
  public void onStatus(PixelblazeConnection connection, double fps, int mem, long uptime, long storageUsed, long storageSize) {
    this.deviceFps.setValue(fps);
    this.deviceMem.setValue(mem);
    this.deviceUptime.setValue(uptime);
    this.deviceStorageUsed.setValue(storageUsed);
    this.deviceStorageSize.setValue(storageSize);
    notifyStatsChanged();
  }

  @Override
  public void onPatternList(PixelblazeConnection connection, Map<String, String> patterns) {
    setPatterns(patterns);
  }

  @Override
  public void onActivePattern(PixelblazeConnection connection, String patternId) {
    int index = this.patternIds.indexOf(patternId);
    if (index >= 0) {
      this.pendingActivePatternId = null;
      this.isLocalChange = true;
      this.patternSelector.setValue(index);
      this.isLocalChange = false;
    } else {
      this.pendingActivePatternId = patternId;
    }
    if (this.connection != null) {
      this.connection.sendGetControls(patternId);
    }
  }

  @Override
  public void onPlaylist(PixelblazeConnection connection, List<PixelblazeConnection.PlaylistItem> items) {
    this.playlistPatternIds.clear();
    this.playlistDurations.clear();
    for (PixelblazeConnection.PlaylistItem item : items) {
      this.playlistPatternIds.add(item.id);
      this.playlistDurations.put(item.id, item.ms);
    }
    notifyPlaylistChanged();
  }

  @Override
  public void onSequencerState(PixelblazeConnection connection, SequencerMode mode, boolean running, int position) {
    this.isLocalChange = true;
    this.sequencerMode.setValue(mode);
    this.sequencerRunning.setValue(running);
    if (position >= 0) {
      this.playlistPosition.setValue(position);
    }
    this.isLocalChange = false;
  }

  @Override
  public void onSequenceTimer(PixelblazeConnection connection, int seconds) {
    this.isLocalChange = true;
    this.sequenceTimer.setValue(seconds);
    this.isLocalChange = false;
  }

  @Override
  public void onControls(PixelblazeConnection connection, Map<String, Object> controls) {
    setControls(controls);
  }

  // State reset

  private void clear() {
    // Bottom-up
    clearControls();
    clearPatterns();
    clearSequencer();
    clearStats();
  }

  private void clearSequencer() {
    this.isLocalChange = true;
    this.sequencerMode.reset();
    this.sequencerRunning.reset();
    this.sequenceTimer.reset();
    this.playlistPosition.reset();
    this.isLocalChange = false;

    this.playlistPatternIds.clear();
    this.playlistDurations.clear();
    notifyPlaylistChanged();
  }

  private void clearStats() {
    this.deviceName.reset();
    this.deviceVersion.reset();
    this.devicePixelCount.reset();
    this.deviceFps.reset();
    this.deviceMem.reset();
    this.deviceStorageUsed.reset();
    this.deviceStorageSize.reset();
    this.deviceUptime.reset();
    notifyStatsChanged();
  }

  // Pattern list

  private void clearPatterns() {
    this.patternIds.clear();
    this.patterns.clear();
    this.pendingActivePatternId = null;
    this.isLocalChange = true;
    this.patternSelector.setOptions(new String[]{NONE});
    this.isLocalChange = false;
    notifyPatternListChanged();
  }

  private void setPatterns(Map<String, String> patterns) {
    this.patterns.clear();
    this.patterns.putAll(patterns);
    this.patternIds.clear();
    this.patternIds.addAll(patterns.keySet());

    String[] options;
    if (this.patternIds.isEmpty()) {
      options = new String[]{NONE};
    } else {
      options = patterns.values().toArray(new String[0]);
    }
    this.isLocalChange = true;
    this.patternSelector.setOptions(options);

    if (this.pendingActivePatternId != null) {
      int index = this.patternIds.indexOf(this.pendingActivePatternId);
      if (index >= 0) {
        this.patternSelector.setValue(index);
      }
      this.pendingActivePatternId = null;
    }

    this.isLocalChange = false;

    notifyPatternListChanged();
  }

  private void selectedPatternChanged() {
    int index = this.patternSelector.getIndex();
    if (this.patternIds.isEmpty() || index >= this.patternIds.size()) {
      return;
    }
    String patternId = this.patternIds.get(index);
    if (this.connection != null) {
      this.connection.sendActivatePattern(patternId);
    }
  }

  /**
   * Returns the pattern ID at the given index in the pattern list, or null.
   */
  public String getPatternId(int index) {
    if (index >= 0 && index < this.patternIds.size()) {
      return this.patternIds.get(index);
    }
    return null;
  }

  // Playlist

  public boolean isInPlaylist(String patternId) {
    return this.playlistPatternIds.contains(patternId);
  }

  public void addToPlaylist(String patternId) {
    if (!this.playlistPatternIds.contains(patternId)) {
      this.playlistPatternIds.add(patternId);
      this.playlistDurations.put(patternId, DEFAULT_PLAYLIST_DURATION_MS);
      sendPlaylist();
      notifyPlaylistChanged();
    }
  }

  public void removeFromPlaylist(String patternId) {
    if (this.playlistPatternIds.remove(patternId)) {
      this.playlistDurations.remove(patternId);
      sendPlaylist();
      notifyPlaylistChanged();
    }
  }

  private void sendPlaylist() {
    if (this.connection != null) {
      List<PixelblazeConnection.PlaylistItem> items = new ArrayList<>();
      for (String id : this.playlistPatternIds) {
        int ms = this.playlistDurations.getOrDefault(id, DEFAULT_PLAYLIST_DURATION_MS);
        items.add(new PixelblazeConnection.PlaylistItem(id, ms));
      }
      this.connection.sendPlaylist(items);
    }
  }

  public void sequencerNext() {
    if (this.connection != null) {
      this.connection.sendNextPattern();
    }
  }

  public void sequencerPrevious() {
    int pos = (int) this.playlistPosition.getValue();
    if (pos > 0 && this.connection != null) {
      this.connection.sendPlaylistPosition(pos - 1);
    }
  }

  public void sequencerRestart() {
    if (this.connection != null) {
      this.connection.sendPlaylistPosition(0);
    }
  }

  // Pattern Controls

  private void clearControls() {
    if (this.mutableControls.isEmpty()) {
      return;
    }
    // Notify listeners so UI controls will unregister, THEN remove the parameters.
    final List<Control> oldControls = new ArrayList<>(this.mutableControls);
    this.mutableControls.clear();
    this.mutableColors.clear();
    notifyControlsChanged();
    for (Control control : oldControls) {
      removeParameter(control.path, true);
      // JKB: Need LXComponent.removeParameter to remove subparameters. For now do it manually:
      if (control.parameter instanceof ColorParameter cp) {
        for (String subKey : cp.subparameters.keySet()) {
          removeParameter(control.path + "/" + subKey, true);
        }
      }
    }
  }

  private void setControls(Map<String, Object> controls) {
    clearControls();
    this.isLocalChange = true;
    for (Map.Entry<String, Object> entry : controls.entrySet()) {
      final String name = entry.getKey();
      final Object value = entry.getValue();
      final ControlType controlType = ControlType.parse(name);
      if (controlType == null) {
        LOG.error("Unrecognized Pixelblaze control (no known prefix): " + name);
        continue;
      }
      final String path = "controls/" + sanitizePath(name);
      final String label = controlType.removePrefix(name);
      switch (controlType) {
        case SLIDER: {
          double v = asDouble(value, 0);
          CompoundParameter p = new CompoundParameter(label, v, 0, 1);
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
        }
        case HSV_PICKER:
        case RGB_PICKER: {
          if (!(value instanceof double[] arr) || arr.length != 3) {
            LOG.error("Pixelblaze color control has invalid value: " + name);
            continue;
          }
          boolean isRgb = (controlType == ControlType.RGB_PICKER);
          int color = isRgb
            ? LXColor.rgbf((float) arr[0], (float) arr[1], (float) arr[2])
            : LXColor.hsb(arr[0] * 359, arr[1] * 100, arr[2] * 100);
          ColorParameter cp = new ColorParameter(label, color);
          addParameter(path, cp);
          this.mutableControls.add(new Control(name, path, controlType, cp));
          this.mutableColors.add(cp);
          break;
        }
        case TOGGLE: {
          BooleanParameter p = new BooleanParameter(label, asBoolean(value));
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
        }
        case INPUT_NUMBER: {
          double v = asDouble(value, 0);
          // Unbounded: wide range used as both bounds and to allow any pattern-provided value.
          CompoundParameter p = new CompoundParameter(label, v, -Double.MAX_VALUE, Double.MAX_VALUE);
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
        }
        case SHOW_NUMBER: {
          double v = asDouble(value, 0);
          MutableParameter p = new MutableParameter(label, v);
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
        }
        case GAUGE: {
          double v = LXUtils.constrain(asDouble(value, 0), 0, 1);
          CompoundParameter p = new CompoundParameter(label, v, 0, 1);
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
        }
        case TRIGGER:
          TriggerParameter p = new TriggerParameter(label);
          addParameter(path, p);
          this.mutableControls.add(new Control(name, path, controlType, p));
          break;
      }
    }
    this.isLocalChange = false;

    notifyControlsChanged();
  }

  private static double asDouble(Object value, double fallback) {
    if (value instanceof Number num) {
      return num.doubleValue();
    }
    if (value instanceof Boolean b) {
      return b ? 1. : 0.;
    }
    return fallback;
  }

  private static boolean asBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    } else if (value instanceof Number num) {
      return num.doubleValue() != 0.;
    }
    return false;
  }

  private static String sanitizePath(String name) {
    return name.toLowerCase().replaceAll("[^a-z0-9]", "_");
  }

  // Notifications

  private void notifyStatsChanged() {
    for (Listener l : this.listeners) {
      l.onStatsChanged(this);
    }
  }

  private void notifyPatternListChanged() {
    for (Listener l : this.listeners) {
      l.onPatternListChanged(this);
    }
  }

  private void notifyPlaylistChanged() {
    for (Listener l : this.listeners) {
      l.onPlaylistChanged(this);
    }
  }

  private void notifyControlsChanged() {
    for (Listener l : this.listeners) {
      l.onControlsChanged(this);
    }
  }

  // Listeners

  public PixelblazeDevice addListener(Listener listener) {
    if (this.listeners.contains(Objects.requireNonNull(listener))) {
      throw new IllegalStateException("Cannot add duplicate listener: " + listener);
    }
    this.listeners.add(listener);
    return this;
  }

  public PixelblazeDevice removeListener(Listener listener) {
    if (!this.listeners.contains(listener)) {
      throw new IllegalStateException("May not remove non-registered listener: " + listener);
    }
    this.listeners.remove(listener);
    return this;
  }

  // Shutdown

  @Override
  public void dispose() {
    disconnect();
    super.dispose();
  }

  /** A control for a Pixelblaze pattern */
  public static class Control {
    public final String name;
    public final String path;
    public final ControlType type;
    public final LXParameter parameter;

    Control(String name, String path, ControlType type, LXParameter parameter) {
      this.name = name;
      this.path = path;
      this.type = type;
      this.parameter = parameter;
    }
  }
}
