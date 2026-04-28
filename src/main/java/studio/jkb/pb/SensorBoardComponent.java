/**
 * Copyright 2026- Justin K. Belcher, Heron Arts LLC
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;

import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import studio.jkb.pb.api.SensorBoard;

import java.util.Arrays;

public class SensorBoardComponent extends LXComponent {

  private static final double ACCELEROMETER_EXPONENT = 2;

  public final SensorBoard sensorBoard;

  // Sensor Board Parameters

  public final BooleanParameter enabled =
    new BooleanParameter("Enabled", false)
      .setDescription("Broadcast Pixelblaze sensor board packets with audio FFT data from Chromatik");

  public final DiscreteParameter fps =
    new DiscreteParameter("FPS", 40, 1, 41)
      .setDescription("Max send rate for sensor board packets");

  public final CompoundParameter accelX =
    new CompoundParameter("Accel X", 0, -32767, 32767)
      .setPolarity(LXParameter.Polarity.BIPOLAR)
      .setNormalizationCurve(BoundedParameter.NormalizationCurve.BIAS_CENTER)
      .setExponent(ACCELEROMETER_EXPONENT)
      .setDescription("Sensor board accelerometer X axis");

  public final CompoundParameter accelY =
    new CompoundParameter("Accel Y", 0, -32767, 32767)
      .setPolarity(LXParameter.Polarity.BIPOLAR)
      .setNormalizationCurve(BoundedParameter.NormalizationCurve.BIAS_CENTER)
      .setExponent(ACCELEROMETER_EXPONENT)
      .setDescription("Sensor board accelerometer Y axis");

  public final CompoundParameter accelZ =
    new CompoundParameter("Accel Z", 0, -32767, 32767)
      .setPolarity(LXParameter.Polarity.BIPOLAR)
      .setNormalizationCurve(BoundedParameter.NormalizationCurve.BIAS_CENTER)
      .setExponent(ACCELEROMETER_EXPONENT)
      .setDescription("Sensor board accelerometer Z axis");

  private final CompoundParameter[] sensorAccel = new CompoundParameter[] {
    accelX, accelY, accelZ
  };

  public final CompoundParameter light =
    new CompoundParameter("Light Sensor", 0, 0, 65520)
      .setDescription("Sensor board light sensor");

  public final CompoundParameter analog1 =
    new CompoundParameter("Analog 1", 0, 0, 65520)
      .setDescription("Sensor board analog input 1");

  public final CompoundParameter analog2 =
    new CompoundParameter("Analog 2", 0, 0, 65520)
      .setDescription("Sensor board analog input 2");

  public final CompoundParameter analog3 =
    new CompoundParameter("Analog 3", 0, 0, 65520)
      .setDescription("Sensor board analog input 3");

  public final CompoundParameter analog4 =
    new CompoundParameter("Analog 4", 0, 0, 65520)
      .setDescription("Sensor board analog input 4");

  public final CompoundParameter analog5 =
    new CompoundParameter("Analog 5", 0, 0, 65520)
      .setDescription("Sensor board analog input 5");

  private final CompoundParameter[] sensorAnalog = new CompoundParameter[] {
    analog1, analog2, analog3, analog4, analog5
  };

  public final BooleanParameter uiExpanded =
    new BooleanParameter("UI Expanded", true)
      .setDescription("Whether the UI section for the Pixelblaze Sensor Board is expanded");

  // Calculated values for sensor inputs, could be modulated
  private final double[] sensorValue = new double[9];

  public SensorBoardComponent(LX lx) {
    super(lx, "sensor");

    addParameter("enabled", this.enabled);
    addParameter("fps", this.fps);
    addParameter("accelX", this.accelX);
    addParameter("accelY", this.accelY);
    addParameter("accelZ", this.accelZ);
    addParameter("light", this.light);
    addParameter("analog1", this.analog1);
    addParameter("analog2", this.analog2);
    addParameter("analog3", this.analog3);
    addParameter("analog4", this.analog4);
    addParameter("analog5", this.analog5);
    addInternalParameter("uiExpanded", this.uiExpanded);

    Arrays.fill(sensorValue, 0.0);
    this.sensorBoard = new SensorBoard(lx);
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    if (parameter == this.enabled) {
      this.sensorBoard.setRunning(this.enabled.isOn());
    } else if (parameter == this.fps) {
      this.sensorBoard.setFramerate(this.fps.getValuef());
    }
  }

  public void loop(double deltaMs) {
    // Input values are potentially modulated so need to be calculated every frame
    calcInputs();

    // Sensor board emulation
    this.sensorBoard.loop();
  }

  private void calcInputs() {
    // Accelerometer
    for (int i = 0; i < 3; i++) {
      double value = this.sensorAccel[i].getValue();
      if (value != this.sensorValue[i]) {
        this.sensorValue[i] = value;
        this.sensorBoard.setAccel(i, (short) value);
      }
    }

    // Light
    final double lightValue = this.light.getValue();
    if (lightValue != this.sensorValue[3]) {
      this.sensorValue[3] = lightValue;
      this.sensorBoard.setLight((short) lightValue);
    }

    // Analog
    for (int i = 0; i < 5; i++) {
      double value = this.sensorAnalog[i].getValue();
      if (value != this.sensorValue[i + 4]) {
        this.sensorValue[i + 4] = value;
        this.sensorBoard.setAnalog(i, (short) value);
      }
    }
  }

  @Override
  public void dispose() {
    this.sensorBoard.dispose();
    super.dispose();
  }
}
