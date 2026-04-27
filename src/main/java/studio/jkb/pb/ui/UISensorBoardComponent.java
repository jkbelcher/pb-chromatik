/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.ui;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.UIColor;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UICollapsibleSection;
import heronarts.glx.ui.component.UIIntegerBox;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.component.UISlider;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameterListener;

import heronarts.lx.utils.LXUtils;
import studio.jkb.pb.SensorBoardComponent;
import studio.jkb.pb.api.SensorBoard;

/**
 * UI for the Pixelblaze Sensor Board emulator, which may be used independently
 * of the main Pixelblaze component.
 */
public class UISensorBoardComponent extends UICollapsibleSection {

  private static final float HORIZONTAL_SPACING = 4;
  private static final float ROW_HEIGHT = 16;
  private static final float LABEL_WIDTH = 64;
  private static final float CONTROL_WIDTH = 80;
  private static final float BAR_PADDING = 0.5f;

  private static final UIColor SENSOR_AUDIO_OFF_COLOR = new UIColor(0xffff8000);

  private final SensorBoardComponent sensor;
    
  public UISensorBoardComponent(UI ui, SensorBoardComponent sensor, float w) {
    super(ui, w, 0, sensor.uiExpanded);
    this.sensor = sensor;
    setTitle("PB SENSOR BOARD");
    setDescription("Pixelblaze Sensor Board Emulator");
    setLayout(Layout.VERTICAL, 4);
    setPadding(2, 0);

    final UIButton sensorEnabled =
      new UIButton.Toggle(w - PADDING - UIButton.Toggle.SIZE, PADDING, sensor.enabled);
    // Visual indicator if system audio is disabled
    final LXParameterListener refreshSensorButton = p -> {
      boolean audioEnabled = ui.lx.engine.audio.enabled.isOn();
      sensorEnabled.setActiveColor(audioEnabled ? ui.theme.primaryColor : SENSOR_AUDIO_OFF_COLOR);
    };
    addListener(sensor.enabled, refreshSensorButton, false);
    addListener(ui.lx.engine.audio.enabled, refreshSensorButton, true);
    addTopLevelComponent(sensorEnabled);

    addChildren(
      newRow(ui, "Framerate",
        new UIIntegerBox(50, ROW_HEIGHT, sensor.fps)
      ),
      UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
        new UILabel(LABEL_WIDTH, ROW_HEIGHT, "Audio")
          .setFont(ui.theme.getControlFont())
          .setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE),
        new UIAudioMeter(ui, sensor.sensorBoard, CONTROL_WIDTH, ROW_HEIGHT)
      ),
      newInputRow(ui, sensor.accelX),
      newInputRow(ui, sensor.accelY),
      newInputRow(ui, sensor.accelZ),
      newInputRow(ui, sensor.light),
      newInputRow(ui, sensor.analog1),
      newInputRow(ui, sensor.analog2),
      newInputRow(ui, sensor.analog3),
      newInputRow(ui, sensor.analog4),
      newInputRow(ui, sensor.analog5)
    );
  }

  private static UI2dContainer newInputRow(UI ui, CompoundParameter parameter) {
    return newRow(ui, parameter.getLabel(),
      new UISlider(UISlider.Direction.HORIZONTAL, CONTROL_WIDTH, ROW_HEIGHT, parameter)
        .setShowLabel(false)
    );
  }

  private static UI2dContainer newRow(UI ui, String label, UI2dComponent component) {
    return UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
      new UILabel(LABEL_WIDTH, ROW_HEIGHT, label)
        .setFont(ui.theme.getControlFont())
        .setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE),
      component
    );
  }

  private class UIAudioMeter extends UI2dComponent {

    private final SensorBoard sensorBoard;

    UIAudioMeter(UI ui, SensorBoard sensorBoard, float w, float h) {
      super(0, 0, w, h);
      this.sensorBoard = sensorBoard;
      setBackgroundColor(ui.theme.controlBackgroundColor);
      addLoopTask(deltaMs -> {
        if (sensor.enabled.isOn()) {
          redraw();
        }
      });
      addListener(sensor.enabled, this.redraw);
    }

    @Override
    protected void onDraw(UI ui, VGraphics vg) {
      if (!sensor.enabled.isOn()) {
        return;
      }
      final float bw = (this.width - BAR_PADDING) / SensorBoard.NUM_BANDS;
      vg.beginPath();
      for (int i = 0; i < SensorBoard.NUM_BANDS; ++i) {
        final float value = LXUtils.clampf(this.sensorBoard.getBandf(i), 0f, 1f);
        if (value <= 0f) {
          continue;
        }
        final float bh = (this.height - (BAR_PADDING * 2)) * value;
        vg.rect(i * bw + BAR_PADDING, this.height - BAR_PADDING - bh, Math.max(1f, bw - BAR_PADDING), bh);
      }
      vg.fillColor(ui.theme.primaryColor);
      vg.fill();
    }
  }

}
