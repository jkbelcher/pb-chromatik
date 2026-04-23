/**
 * Copyright 2026- Justin K. Belcher, Heron Arts LLC
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.ui;

import java.util.ArrayList;
import java.util.List;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UIColorPicker;
import heronarts.glx.ui.component.UIDoubleBox;
import heronarts.glx.ui.component.UIDropMenu;
import heronarts.glx.ui.component.UIItemList;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.component.UIMeter;
import heronarts.glx.ui.component.UISlider;
import heronarts.glx.ui.component.UIToggleSet;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.color.ColorParameter;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.parameter.MutableParameter;
import heronarts.lx.parameter.TriggerParameter;

import studio.jkb.pb.PixelblazeComponent;
import studio.jkb.pb.api.PixelblazeDevice;
import studio.jkb.pb.api.SequencerMode;

/**
 * UI controls for a connected Pixelblaze device.
 */
public class UIPixelblazeDevice extends UI2dContainer implements PixelblazeDevice.Listener {

  private static final float VERTICAL_SPACING = 4;
  private static final float HORIZONTAL_SPACING = 4;
  private static final float ROW_HEIGHT = 16;
  private static final float LABEL_WIDTH = 64;
  private static final float CONTROL_X = LABEL_WIDTH + HORIZONTAL_SPACING;
  private static final float ICON_BUTTON_WIDTH = 16;
  private static final float PATTERN_LIST_HEIGHT = 120;

  private final UI ui;
  private PixelblazeDevice device;

  private final UIStats stats;

  private final UISlider brightnessSlider;
  private final UIToggleSet sequencerModeToggle;
  private final UI2dContainer playbackRow;
  private final UIButton.Action restartButton;
  private final UIButton.Action previousButton;
  private final UIButton playButton;
  private final UIDoubleBox sequenceTimer;
  private final UIItemList.ScrollList patternList;
  private final UI2dContainer controlsContainer;

  private final LXParameterListener patternSelectorListener;
  private final LXParameterListener connectedListener;
  private final LXParameterListener sequencerModeListener;

  public UIPixelblazeDevice(UI ui, float w) {
    super(0, 0, w, 0);
    this.ui = ui;
    setLayout(Layout.VERTICAL, VERTICAL_SPACING);
    final float contentWidth = getContentWidth();

    // Stats popup panel
    this.stats = new UIStats(ui);

    // Sequencer playback controls
    float bx = 0;
    this.restartButton = (UIButton.Action)
      new UIButton.Action(bx, 0, ICON_BUTTON_WIDTH, ROW_HEIGHT, () -> {
        if (this.device != null) {
          this.device.sequencerRestart();
        }
      })
        .setIcon(ui.theme.iconReset)
        .setBorderRounding(0);
    bx += ICON_BUTTON_WIDTH + HORIZONTAL_SPACING;

    this.previousButton = (UIButton.Action)
      new UIButton.Action(bx, 0, ICON_BUTTON_WIDTH, ROW_HEIGHT, () -> {
        if (this.device != null) {
          this.device.sequencerPrevious();
        }
      })
        .setIcon(ui.theme.iconPrev)
        .setBorderRounding(0);
    bx += ICON_BUTTON_WIDTH + HORIZONTAL_SPACING;

    this.playButton = (UIButton)
      new UIButton(bx, 0, ICON_BUTTON_WIDTH, ROW_HEIGHT)
        .setIcon(ui.theme.iconPlay)
        .setBorderRounding(0);
    bx += ICON_BUTTON_WIDTH + HORIZONTAL_SPACING;

    UIButton.Action nextButton = new UIButton.Action(bx, 0, ICON_BUTTON_WIDTH, ROW_HEIGHT, () -> {
      if (this.device != null) {
        this.device.sequencerNext();
      }
    });
    nextButton.setIcon(ui.theme.iconNext).setBorderRounding(0);
    bx += ICON_BUTTON_WIDTH + HORIZONTAL_SPACING;

    // Cycle time for shuffle mode
    final float timerWidth = 42;
    this.sequenceTimer = (UIDoubleBox)
      new UIDoubleBox(bx, 0, timerWidth, ROW_HEIGHT)
        .setNormalizedMouseEditing(false)
        .setShiftMultiplier(60);

    this.playbackRow = new UI2dContainer(0, 0, contentWidth, ROW_HEIGHT).addChildren(
      this.restartButton,
      this.previousButton,
      this.playButton,
      nextButton,
      this.sequenceTimer
    );

    addChildren(
      // Brightness slider
      UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
        new UILabel.Control(ui, LABEL_WIDTH, ROW_HEIGHT, "Brightness"),
        this.brightnessSlider = (UISlider)
          new UISlider(0, 0, contentWidth - CONTROL_X, ROW_HEIGHT)
            .setShowLabel(false)
      ),

      // Sequencer mode
      this.sequencerModeToggle =
        new UIToggleSet(0, 0, contentWidth, 18)
          .setEvenSpacing()
          .setActiveColor(ui.theme.primaryColor),

      // Playback controls
      this.playbackRow,

      // Pattern list
      this.patternList = (UIItemList.ScrollList)
        new UIItemList.ScrollList(ui, 0, 0, contentWidth, PATTERN_LIST_HEIGHT)
          .setSingleClickActivate(true)
          .setShowCheckboxes(true),

      // Color sync row (bound to the singleton component, not per-device)
      buildColorSyncRow(ui, contentWidth),

      // Pattern controls
      this.controlsContainer =
        new UI2dContainer(0, 0, contentWidth, 0)
          .setLayout(Layout.VERTICAL, VERTICAL_SPACING)
    );

    this.patternSelectorListener = p -> {
      if (this.device == null) {
        return;
      }
      int index = this.device.patternSelector.getIndex();
      if (index >= 0 && index < this.patternList.getItems().size()) {
        this.patternList.setFocusIndex(index);
      }
    };
    this.connectedListener = p -> this.stats.setVisible(this.device != null && this.device.connected.isOn());
    this.sequencerModeListener = p -> updateSequencerControls();

    // Start with no device visible
    setVisible(false);
  }

  private static UI2dContainer buildColorSyncRow(UI ui, float contentWidth) {
    final float labelWidth = 56;
    final float indexWidth = 30;
    final float syncWidth = contentWidth - labelWidth - HORIZONTAL_SPACING - indexWidth - HORIZONTAL_SPACING;
    final PixelblazeComponent component = PixelblazeComponent.get();
    return UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
      new UILabel.Control(ui, labelWidth, ROW_HEIGHT, "Color Sync"),
      new UIDropMenu(0, 0, syncWidth, ROW_HEIGHT, component.colorSyncMode)
        .setMenuWidth(100),
      new UIDropMenu(0, 0, indexWidth, ROW_HEIGHT, component.paletteIndex)
    );
  }

  public UIPixelblazeDevice setDevice(PixelblazeDevice device) {
    if (this.device == device) {
      return this;
    }

    if (this.device != null) {
      this.device.removeListener(this);
      this.device.patternSelector.removeListener(this.patternSelectorListener);
      this.device.connected.removeListener(this.connectedListener);
      this.device.sequencerMode.removeListener(this.sequencerModeListener);
    }

    this.controlsContainer.removeAllChildren();
    this.device = device;

    if (device != null) {
      // Re-bind persistent parameter widgets
      this.brightnessSlider.setParameter(device.brightness);
      this.sequenceTimer.setParameter(device.sequenceTimer);
      this.playButton.setParameter(device.sequencerRunning);
      this.sequencerModeToggle.setParameter(device.sequencerMode);

      // Listen for changes to device's patterns and controls
      device.addListener(this);
      device.patternSelector.addListener(this.patternSelectorListener);
      device.connected.addListener(this.connectedListener);
      device.sequencerMode.addListener(this.sequencerModeListener);

      refreshPatternList();
      onStatsChanged(device);
      onControlsChanged(device);
      this.stats.setVisible(device.connected.isOn());
      updateSequencerControls();

      setVisible(true);
    } else {
      this.stats.setVisible(false);

      this.brightnessSlider.setParameter(null);
      this.sequenceTimer.setParameter(null);
      this.playButton.removeParameter();
      this.sequencerModeToggle.setParameter(null);

      setVisible(false);
    }

    return this;
  }

  /**
   * Get the stats overlay for use by info button
   */
  UIStats getStatsOverlay() {
    return this.stats;
  }

  private void updateSequencerControls() {
    if (this.device == null) {
      return;
    }
    SequencerMode mode = this.device.sequencerMode.getEnum();
    boolean active = mode != SequencerMode.OFF;
    boolean isShuffle = mode == SequencerMode.SHUFFLE_ALL;
    boolean isPlaylist = mode == SequencerMode.PLAYLIST;
    this.playbackRow.setVisible(active);
    this.restartButton.setVisible(isPlaylist);
    this.previousButton.setVisible(isPlaylist);
    this.sequenceTimer.setVisible(isShuffle);
  }

  @Override
  public void onStatsChanged(PixelblazeDevice device) {
    this.stats.setDevice(device);
  }

  private void refreshPatternList() {
    final PixelblazeDevice device = this.device;
    if (device == null) {
      this.patternList.setItems(new ArrayList<>());
      return;
    }
    String[] options = device.patternSelector.getOptions();
    List<UIItemList.Item> items = new ArrayList<>();
    for (int i = 0; i < options.length; ++i) {
      final int index = i;
      final String label = options[i];
      final String patternId = device.getPatternId(index);
      items.add(new UIItemList.Item() {
        @Override
        public String getLabel() {
          return label;
        }

        @Override
        public boolean isActive() {
          return device.patternSelector.getIndex() == index;
        }

        @Override
        public int getActiveColor(UI ui) {
          return ui.theme.primaryColor.get();
        }

        @Override
        public void onActivate() {
          device.patternSelector.setValue(index);
        }

        @Override
        public boolean isChecked() {
          return patternId != null && device.isInPlaylist(patternId);
        }

        @Override
        public void onCheck(boolean checked) {
          if (patternId != null) {
            if (checked) {
              device.addToPlaylist(patternId);
            } else {
              device.removeFromPlaylist(patternId);
            }
          }
        }
      });
    }
    this.patternList.setItems(items);

    int activeIndex = device.patternSelector.getIndex();
    if (activeIndex >= 0 && activeIndex < items.size()) {
      this.patternList.setFocusIndex(activeIndex);
    }
  }

  @Override
  public void onPatternListChanged(PixelblazeDevice device) {
    refreshPatternList();
  }

  @Override
  public void onPlaylistChanged(PixelblazeDevice device) {
    this.patternList.redraw();
  }

  @Override
  public void onControlsChanged(PixelblazeDevice device) {
    this.controlsContainer.removeAllChildren();
    final float controlWidth = getContentWidth() - CONTROL_X;

    for (PixelblazeDevice.Control control : device.controls) {
      final LXParameter p = control.parameter;
      switch (control.type) {
        case SLIDER -> addRow(p,
          new UISlider(controlWidth, ROW_HEIGHT, (CompoundParameter) p)
            .setShowLabel(false)
        );
        case HSV_PICKER, RGB_PICKER -> addRow(p,
          new UIColorPicker(ROW_HEIGHT, ROW_HEIGHT, (ColorParameter) p)
        );
        case TOGGLE -> addRow(p,
          new UIButton(controlWidth, ROW_HEIGHT, (BooleanParameter) p)
        );
        case TRIGGER -> addRow(p,
          new UIButton(controlWidth, ROW_HEIGHT, (TriggerParameter) p)
        );
        case INPUT_NUMBER -> addRow(p,
          new UIDoubleBox(0, 0, controlWidth, ROW_HEIGHT, (CompoundParameter) p)
            .setNormalizedMouseEditing(false)
        );
        case SHOW_NUMBER -> addRow(p,
          new UIShowNumber(this.ui, controlWidth, ROW_HEIGHT, (MutableParameter) p)
        );
        case GAUGE -> addRow(p,
          UIMeter.newHorizontalMeter(this.ui, (CompoundParameter) p, controlWidth, ROW_HEIGHT)
        );
      };
    }
  }

  private void addRow(LXParameter p, UI2dComponent uiControl) {
    UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
      new UILabel.Control(this.ui, LABEL_WIDTH, ROW_HEIGHT, p.getLabel()),
      uiControl
    ).addToContainer(this.controlsContainer);
  }

  @Override
  public void dispose() {
    setDevice(null);
    super.dispose();
  }

  static class UIStats extends UI2dContainer {

    private static final float NAME_WIDTH = 48;

    private final UILabel name, version, pixels, fps, uptime, memory, storage;

    private UIStats(UI ui) {
      super(0, 0, 200, 0);
      setLayout(Layout.VERTICAL);
      setBackgroundColor(ui.theme.listBackgroundColor);
      setBorderColor(ui.theme.contextBorderColor);
      setBorderRounding(4);
      setPadding(4, 0);

      final float labelWidth = getContentWidth() - NAME_WIDTH - HORIZONTAL_SPACING;

      addChildren(
        newRow(ui, "Name", this.name = newLabel(ui, labelWidth)),
        newRow(ui, "Version", this.version = newLabel(ui, labelWidth)),
        newRow(ui, "Pixels", this.pixels = newLabel(ui, labelWidth)),
        newRow(ui, "FPS", this.fps = newLabel(ui, labelWidth)),
        newRow(ui, "Uptime", this.uptime = newLabel(ui, labelWidth)),
        newRow(ui, "Memory", this.memory = newLabel(ui, labelWidth)),
        newRow(ui, "Storage", this.storage = newLabel(ui, labelWidth))
      );
    }

    private static UI2dContainer newRow(UI ui, String name, UILabel valueLabel) {
      return UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
        new UILabel(NAME_WIDTH, ROW_HEIGHT, name)
          .setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE)
          .setLeftMargin(6),
        valueLabel
        );
    }

    private static UILabel newLabel(UI ui, float w) {
      UILabel label = new UILabel(w, ROW_HEIGHT, "");
      label.setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE);
      label.setFont(ui.theme.getControlFont());
      return label;
    }

    void setDevice(PixelblazeDevice device) {
      this.name.setLabel(device.deviceName.getString());

      String version = device.deviceVersion.getString();
      this.version.setLabel(!version.isEmpty() ? "v" + version : "");

      int pixels = (int) device.devicePixelCount.getValue();
      this.pixels.setLabel(String.valueOf(pixels));

      this.fps.setLabel(String.format("%.1f", device.deviceFps.getValue()));

      long uptimeMs = (long) device.deviceUptime.getValue();
      this.uptime.setLabel(uptimeMs > 0 ? formatUptime(uptimeMs) : "");

      int deviceMem = device.deviceMem.getValuei();
      this.memory.setLabel(formatBytes(deviceMem));

      long storageUsed = (long) device.deviceStorageUsed.getValue();
      long storageSize = (long) device.deviceStorageSize.getValue();
      if (storageSize > 0) {
        int percent = (int) (100.0 * storageUsed / storageSize);
        this.storage.setLabel(percent + "% (" + formatBytes(storageUsed) + " / " + formatBytes(storageSize) + ")");
      } else {
        this.storage.setLabel("");
      }
    }

    private static String formatUptime(long ms) {
      long totalSeconds = ms / 1000;
      long days = totalSeconds / 86400;
      long hours = (totalSeconds % 86400) / 3600;
      long minutes = (totalSeconds % 3600) / 60;
      if (days > 0) {
        return days + "d " + hours + "h " + minutes + "m";
      } else if (hours > 0) {
        return hours + "h " + minutes + "m";
      } else {
        return minutes + "m";
      }
    }

    private static String formatBytes(long bytes) {
      if (bytes >= 1000000) {
        return String.format("%.1fMB", bytes / 1000000.0);
      } else if (bytes >= 1000) {
        return String.format("%dKB", bytes / 1000);
      }
      return bytes + "B";
    }
  }

}
