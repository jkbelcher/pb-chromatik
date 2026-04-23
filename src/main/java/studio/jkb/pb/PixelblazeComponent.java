/**
 * Copyright 2026- Justin K. Belcher, Heron Arts LLC
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonObject;
import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXLoopTask;
import heronarts.lx.color.ColorParameter;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.color.LXPalette;
import heronarts.lx.color.LXSwatch;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;

import studio.jkb.pb.api.PixelblazeDevice;
import studio.jkb.pb.api.PixelblazeDiscovery;

/**
 * Top-level component for Pixelblaze-Chromatik plugin, runs as a child of LX engine.
 * Owns the discovery service and the list of {@link PixelblazeDevice} instances.
 */
public class PixelblazeComponent extends LXComponent
  implements LXLoopTask, PixelblazeDiscovery.Listener, PixelblazeDevice.Listener {

  private static final String NONE = "(none)";

  /** Minimum interval between Pixelblaze color sends in CHROMATIK_LEADS mode. */
  private static final long CHROMATIK_LEADS_INTERVAL_MS = 100;

  public enum ColorSync {
    OFF("Off"),
    PIXELBLAZE_LEADS("Pixelblaze Leads"),
    CHROMATIK_LEADS("Chromatik Leads"),;

    public final String label;

    ColorSync(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  private static PixelblazeComponent current;

  public static PixelblazeComponent get() {
    return current;
  }

  // Listeners

  public interface Listener {
    public default void onDeviceListChanged(PixelblazeComponent pb) {}
    public default void onActiveDeviceChanged(PixelblazeComponent pb, PixelblazeDevice device) {}
  }

  private final List<Listener> listeners = new ArrayList<>();

  // Discovery

  private final PixelblazeDiscovery discovery;

  private final List<PixelblazeDevice> devices = new ArrayList<>();

  // Active device (currently focused for the UI)

  private PixelblazeDevice activeDevice;

  // Color sync
  private final PaletteTracker paletteTracker;
  private ColorParameter pbColor;
  private final LXParameterListener pbColorListener;

  private long nextPbColorSend = 0;
  private boolean hasPendingPbColor = false;
  private int pendingPbColor = 0;

  // Parameters

  public final BooleanParameter enabled =
    new BooleanParameter("Enabled", false)
      .setDescription("Enable Pixelblaze discovery");

  public final DiscreteParameter deviceSelector =
    new DiscreteParameter("Device", new String[]{NONE})
      .setDescription("Select a Pixelblaze device to connect to");

  public final BooleanParameter connected =
    new BooleanParameter("Connected", false)
      .setDescription("[Read-only] Whether the active Pixelblaze is currently connected");

  public final EnumParameter<ColorSync> colorSyncMode =
    new EnumParameter<>("Color Sync", ColorSync.OFF)
      .setDescription("Color sync mode between Pixelblaze and Chromatik global palette");

  public final DiscreteParameter paletteIndex =
    new LXPalette.IndexSelector("Palette Index")
      .setDescription("Swatch index in the global palette for color sync with Chromatik");

  private final LXParameterListener activeConnectedListener =
    p -> this.connected.setValue(((BooleanParameter) p).isOn());

  // Constructor

  public PixelblazeComponent(LX lx) {
    super(lx, "pb");
    if (current != null) {
      throw new IllegalStateException("Only one instance of " + getClass().getSimpleName() + " is permitted");
    }
    current = this;

    addParameter("enabled", this.enabled);
    addParameter(KEY_DEVICE_SELECTOR, this.deviceSelector);
    addParameter(KEY_CONNECTED, this.connected);
    addParameter("colorSyncMode", this.colorSyncMode);
    addParameter("paletteIndex", this.paletteIndex);

    this.discovery = new PixelblazeDiscovery(lx, this);

    this.paletteTracker = new PaletteTracker(lx, this::paletteColorChanged);
    this.pbColorListener = p -> {
      if (this.colorSyncMode.getEnum() == ColorSync.PIXELBLAZE_LEADS) {
        int color = this.pbColor.getColor();
        this.paletteTracker.setColor(color);
      }
    };
  }

  // Parameter changes

  @Override
  public void onParameterChanged(LXParameter parameter) {
    if (parameter == this.enabled) {
      enabledChanged();
    } else if (parameter == this.deviceSelector) {
      selectedDeviceChanged();
    } else if (parameter == this.colorSyncMode) {
      colorSyncModeChanged();
    } else if (parameter == this.paletteIndex) {
      this.paletteTracker.setIndex(this.paletteIndex.getValuei() - 1);
    }
  }

  private void enabledChanged() {
    if (this.enabled.isOn()) {
      this.discovery.start();
      this.lx.engine.addLoopTask(this);
    } else {
      this.discovery.stop();
      setActiveDevice(null);
      clearDevices();
      this.lx.engine.removeLoopTask(this);
    }
  }

  // LXLoopTask

  @Override
  public void loop(double deltaMs) {
    this.discovery.loop(deltaMs);

    // Throttled Chromatik -> Pixelblaze color sync
    if (this.hasPendingPbColor) {
      long now = System.currentTimeMillis();
      if (now >= this.nextPbColorSend) {
        if (this.pbColor != null) {
          this.pbColor.setColor(this.pendingPbColor);
        }
        this.hasPendingPbColor = false;
        this.nextPbColorSend = now + CHROMATIK_LEADS_INTERVAL_MS;
      }
    }

    // Loop parameter sending queue for active device
    if (this.activeDevice != null) {
      this.activeDevice.loop(deltaMs);
    }
  }

  // PixelblazeDiscovery.Listener

  @Override
  public void onDeviceDiscovered(PixelblazeDevice device) {
    for (PixelblazeDevice existing : this.devices) {
      if (existing.equals(device)) {
        existing.touch();
        return;
      }
    }
    this.devices.add(device);
    LOG.log("Device discovered: " + device.getLabel());
    rebuildDeviceList();
    // Auto-select first device, but respect cooldown if it recently failed to connect
    if (this.deviceSelector.getIndex() == 0) {
      long cooldownMs = device.reconnectCooldownRemainingMs();
      if (cooldownMs > 0) {
        LOG.log("Skipping auto-connect to " + device.getLabel() + " (cooldown, " + (cooldownMs / 1000) + "s remaining)");
        return;
      }
      // Auto-select
      this.deviceSelector.setValue(this.devices.size());
    }
  }

  @Override
  public void onDeviceExpired(PixelblazeDevice device) {
    if (this.devices.remove(device)) {
      LOG.log("Device expired: " + device.getLabel());
      if (this.activeDevice == device) {
        setActiveDevice(null);
        this.deviceSelector.setValue(0);
      }
      device.dispose();
      rebuildDeviceList();
    }
  }

  // Devices

  private void clearDevices() {
    for (PixelblazeDevice device : this.devices) {
      device.dispose();
    }
    this.devices.clear();
    rebuildDeviceList();
  }

  private void rebuildDeviceList() {
    String[] options = new String[this.devices.size() + 1];
    options[0] = NONE;
    for (int i = 0; i < this.devices.size(); ++i) {
      options[i + 1] = this.devices.get(i).getLabel();
    }
    this.deviceSelector.setOptions(options);
    for (Listener l : this.listeners) {
      l.onDeviceListChanged(this);
    }
  }

  private void selectedDeviceChanged() {
    int index = this.deviceSelector.getIndex() - 1; // offset for "(none)" at index 0
    if (index < 0 || index >= this.devices.size()) {
      setActiveDevice(null);
      return;
    }
    setActiveDevice(this.devices.get(index));
  }

  private void setActiveDevice(PixelblazeDevice device) {
    if (this.activeDevice == device) {
      return;
    }

    if (this.activeDevice != null) {
      this.activeDevice.connected.removeListener(this.activeConnectedListener);
      // Disconnect before removing listener, to unregister the color control when it's disposed
      this.activeDevice.disconnect();
      this.activeDevice.removeListener(this);
    }
    this.activeDevice = device;
    this.connected.setValue(false);
    if (device != null) {
      device.connected.addListener(this.activeConnectedListener, true);
      device.addListener(this);
      device.connect();
    }
    refreshPbColor();
    for (Listener l : this.listeners) {
      l.onActiveDeviceChanged(this, device);
    }
  }

  public PixelblazeDevice getActiveDevice() {
    return this.activeDevice;
  }

  // Color sync

  private void colorSyncModeChanged() {
    final ColorSync mode = this.colorSyncMode.getEnum();

    // Minimize "listening" to a LXDynamicColor, which requires calculating the color every frame
    if (mode == ColorSync.CHROMATIK_LEADS) {
      this.paletteTracker.startListening();
    } else {
      this.paletteTracker.stopListening();
      this.hasPendingPbColor = false;
    }
  }

  /**
   * Value change in the global color we are tracking
   */
  public void paletteColorChanged(int color) {
    // Should only get called in sync mode CHROMATIK_LEADS
    this.pendingPbColor = color;
    this.hasPendingPbColor = true;
  }

  @Override
  public void onControlsChanged(PixelblazeDevice device) {
    // Controls changed on active device
    refreshPbColor();
  }

  private void refreshPbColor() {
    // Determine Pixelblaze color parameter to use for sync (either direction)
    ColorParameter newColor = null;
    if (this.activeDevice != null && !this.activeDevice.colors.isEmpty()) {
      newColor = this.activeDevice.colors.getFirst();
    }

    // Register Pixelblaze color parameter
    if (this.pbColor != newColor) {
      if (this.pbColor != null) {
        this.pbColor.removeListener(this.pbColorListener);
      }

      this.pbColor = newColor;
      this.nextPbColorSend = System.currentTimeMillis();
      this.hasPendingPbColor = false;

      if (this.pbColor != null) {
        // ColorParameter is initialized to the PB color, so fire the listener and sync to us
        this.pbColor.addListener(this.pbColorListener, true);
        // Or sync to it
        if (this.colorSyncMode.getEnum() == ColorSync.CHROMATIK_LEADS) {
          this.paletteTracker.bang();
        }
      }
    }
  }

  // Listeners

  public PixelblazeComponent addListener(Listener listener) {
    if (this.listeners.contains(Objects.requireNonNull(listener))) {
      throw new IllegalStateException("Cannot add duplicate listener: " + listener);
    }
    this.listeners.add(listener);
    return this;
  }

  public PixelblazeComponent removeListener(Listener listener) {
    if (!this.listeners.contains(listener)) {
      throw new IllegalStateException("May not remove non-registered listener: " + listener);
    }
    this.listeners.remove(listener);
    return this;
  }

  // Shutdown

  @Override
  public void dispose() {
    this.enabled.setValue(false);
    setActiveDevice(null);

    this.paletteTracker.dispose();
    current = null;
    super.dispose();
  }

  // Serialization

  private static final String KEY_DEVICE_SELECTOR = "deviceSelector";
  private static final String KEY_CONNECTED = "connected";

  @Override
  public void save(LX lx, JsonObject obj) {
    super.save(lx, obj);
    removeNonSerializedParameters(obj);
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    removeNonSerializedParameters(obj);
    super.load(lx, obj);
  }

  private void removeNonSerializedParameters(JsonObject obj) {
    removeParameter(obj, KEY_DEVICE_SELECTOR);
    removeParameter(obj, KEY_CONNECTED);
  }

  private static void removeParameter(JsonObject obj, String parameterKey) {
    if (obj.has(KEY_PARAMETERS)) {
      final JsonObject parametersObj = obj.getAsJsonObject(KEY_PARAMETERS);
      if (parametersObj.has(parameterKey)) {
        parametersObj.remove(parameterKey);
      }
    }
  }

  // Inner classes

  /**
   * Helper class for listening to color changes at a given palette index
   */
  static class PaletteTracker implements LXSwatch.Listener, LXLoopTask {

    private static final int NO_COLOR = -1;

    interface Callback {
      void paletteColorChanged(int color);
    }

    private final Callback callback;

    private final LX lx;
    private final LXSwatch swatch; // == lx.engine.palette.swatch

    // Index & color to track
    private int paletteIndex = 0;
    private LXDynamicColor dynamicColor = null;
    private int lastColor = NO_COLOR;

    private boolean isListening = false;

    PaletteTracker(LX lx, Callback callback) {
      this.lx = lx;
      this.callback = Objects.requireNonNull(callback);
      this.swatch = this.lx.engine.palette.swatch;
      this.swatch.addListener(this);
      this.lx.engine.addLoopTask(this);
    }

    /**
     * Set the index of the target color in the global palette
     */
    public void setIndex(int index) {
      if (index < 0 || index >= LXSwatch.MAX_COLORS) {
        throw new IllegalArgumentException("Invalid palette index: " + index);
      }
      if (this.paletteIndex != index) {
        this.paletteIndex = index;
        refreshDynamicColor();
      }
    }

    @Override
    public void colorAdded(LXSwatch swatch, LXDynamicColor color) {
      refreshDynamicColor();
    }

    @Override
    public void colorRemoved(LXSwatch swatch, LXDynamicColor color) {
      refreshDynamicColor();
    }

    private void refreshDynamicColor() {
      LXDynamicColor newColor = null;
      if (this.swatch.colors.size() > this.paletteIndex) {
        newColor = this.swatch.colors.get(this.paletteIndex);
      }
      if (this.dynamicColor != newColor) {
        this.dynamicColor = newColor;
        this.lastColor = NO_COLOR;
      }
    }

    PaletteTracker startListening() {
      this.isListening = true;
      return this;
    }

    PaletteTracker stopListening() {
      this.isListening = false;
      return this;
    }

    void bang() {
      // Reset the lastColor to force sync on next loop. Escapes the current isInternal flag.
      this.lastColor = NO_COLOR;
    }

    @Override
    public void loop(double deltaMs) {
      if (this.isListening && this.dynamicColor != null) {
        int color = this.dynamicColor.getColor();
        if (this.lastColor != color) {
          this.lastColor = color;
          notifyColorChanged(color);
        }
      }
    }

    /**
     * The color in the desired index has changed
     */
    private void notifyColorChanged(int color) {
      this.callback.paletteColorChanged(color);
    }

    /**
     * Set the target palette index to this color
     */
    void setColor(int color) {
      if (this.dynamicColor == null) {
        // Increase number of colors in swatch to make the index available
        while (this.swatch.colors.size() <= this.paletteIndex) {
          this.swatch.addColor();
        }
        refreshDynamicColor();
      }
      this.dynamicColor.color.setColor(color);
    }

    void dispose() {
      this.swatch.removeListener(this);
      this.lx.engine.removeLoopTask(this);
    }
  }
}
