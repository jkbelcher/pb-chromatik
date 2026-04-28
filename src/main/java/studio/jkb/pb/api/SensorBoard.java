/**
 * Copyright 2026- Justin K. Belcher, ZRanger1
 *
 * Thanks to ZRanger1 for first mapping this in java:
 *   https://github.com/zranger1/SoundServerFX
 *
 * References:
 *   https://electromage.com/docs/sensor-expansion-board
 *   https://github.com/simap/pixelblaze_sensor_board
 *   https://github.com/simap/PixelblazeSensorBoardLibrary
 *
 * @author Justin K. Belcher <justin@jkb.studio>
 * @author ZRanger1
 */

package studio.jkb.pb.api;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import heronarts.lx.LX;
import heronarts.lx.audio.DecibelMeter;
import heronarts.lx.audio.FourierTransform;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.utils.LXUtils;

import studio.jkb.pb.LOG;

/**
 * Emulates a Pixelblaze Sensor Expansion Board by broadcasting 104-byte
 * UDP sensor packets to the local network. The frequency bands are populated
 * from the Chromatik audio engine FFT, mapped to the 32 documented Pixelblaze
 * sensor board bands.
 *
 * Packets are broadcast to 255.255.255.255 on port 1889.
 */
public class SensorBoard {

  private static final int BROADCAST_PORT = 1889;
  private static final int PACKET_SIZE = 104;
  private static final int EXPANSION_TYPE = 1;
  public static final int NUM_BANDS = 32;
  private static final float FFT_SCALE = 8192f;

  /**
   * Frequency band edges (Hz) for the Pixelblaze sensor board's 32 bands
   */
  private static final float[][] BAND_EDGE = {
    {   12,    50 },
    {   38,    75 },
    {   50,   100 },
    {   75,   125 },
    {  100,   163 },
    {  125,   195 },
    {  163,   234 },
    {  195,   312 },
    {  234,   391 },
    {  312,   469 },
    {  391,   586 },
    {  469,   703 },
    {  586,   859 },
    {  703,   976 },
    {  859,  1170 },
    {  976,  1370 },
    { 1170,  1560 },
    { 1370,  1800 },
    { 1560,  2070 },
    { 1800,  2380 },
    { 2070,  2730 },
    { 2380,  3120 },
    { 2730,  3590 },
    { 3120,  4100 },
    { 3590,  4650 },
    { 4100,  5310 },
    { 4650,  6020 },
    { 5310,  6840 },
    { 6020,  7770 },
    { 6840,  8790 },
    { 7770,  9960 },
    { 8790, 11000 },
  };

  private final LX lx;
  private final ByteBuffer buffer;
  private final DatagramPacket packet;

  private DatagramSocket socket;
  private InetAddress broadcastAddress;
  private long nextSend = 0;
  private float fps = 40;
  private boolean isAudioRegistered = false;

  private final float[] bands = new float[NUM_BANDS];
  private final float[] rmsBands = new float[NUM_BANDS];
  private final double[] bandOctaves = new double[NUM_BANDS];

  private final short[] accel = new short[3];
  private short light = 0;
  private final short[] analog = new short[5];

  public SensorBoard(LX lx) {
    this.lx = lx;
    this.buffer = ByteBuffer.allocate(PACKET_SIZE);
    this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    this.packet = new DatagramPacket(this.buffer.array(), PACKET_SIZE);

    for (int i = 0; i < NUM_BANDS; ++i) {
      final double centerHz = Math.sqrt((double) BAND_EDGE[i][0] * BAND_EDGE[i][1]);
      this.bandOctaves[i] = Math.log(centerHz / FourierTransform.BASE_BAND_HZ) / Math.log(2);
    }

    Arrays.fill(bands, 0);
    Arrays.fill(rmsBands, 0);
    Arrays.fill(accel, (short) 0);
    Arrays.fill(analog, (short) 0);
  }

  // State

  public void setFramerate(float fps) {
    this.fps = LXUtils.maxf(fps, 1f);
  }

  public boolean isRunning() {
    return this.socket != null;
  }

  public SensorBoard setRunning(boolean shouldRun) {
    if (shouldRun) {
      if (!isRunning()) {
        start();
      }
    } else {
      if (isRunning()) {
        stop();
      }
    }
    return this;
  }

  public void start() {
    if (isRunning()) {
      throw new IllegalStateException("Sensor board is already running");
    }

    try {
      this.broadcastAddress = InetAddress.getByName("255.255.255.255");
      this.socket = new DatagramSocket();
      this.socket.setBroadcast(true);
      this.packet.setAddress(this.broadcastAddress);
      this.packet.setPort(BROADCAST_PORT);
      this.nextSend = 0;

      this.lx.engine.audio.meter.addProcessor(this.processor);
      this.isAudioRegistered = true;

      LOG.log("Sensor board started");
    } catch (UnknownHostException ux) {
      LOG.error(ux, "Failed to resolve broadcast address for sensor board");
      stop();
    } catch (Exception e) {
      LOG.error(e, "Failed to start sensor board");
      stop();
    }
  }

  public void stop() {
    if (this.isAudioRegistered) {
      this.lx.engine.audio.meter.removeProcessor(this.processor);
      this.isAudioRegistered = false;
    }
    if (this.socket != null) {
      this.socket.close();
      this.socket = null;
      this.broadcastAddress = null;
      Arrays.fill(this.rmsBands, 0f);
      Arrays.fill(this.bands, 0f);
      LOG.log("Sensor board stopped");
    }
  }

  // Audio Processing

  private final GraphicMeter.Processor processor = new GraphicMeter.Processor() {
    @Override
    public void onMeterAudioFrame(GraphicMeter meter) {
      // Use the adjustments from the global Audio meter with our custom frequency bands
      final FourierTransform fft = meter.fft;
      final int fftSize = fft.getSize();

      final double gain = meter.gain.getValue();
      final double range = meter.range.getValue();
      final double slope = meter.slope.getValue();

      final double attackGain = Math.exp(-meter.getBufferSize() / (meter.attack.getValue() * meter.getSampleRate() * .001));
      final double releaseGain = Math.exp(-meter.getBufferSize() / (meter.release.getValue() * meter.getSampleRate() * .001));

      for (int i = 0; i < NUM_BANDS; ++i) {
        final float rmsLevel = fft.getAverage(BAND_EDGE[i][0], BAND_EDGE[i][1]) / fftSize;
        final double rmsGain = (rmsLevel >= rmsBands[i]) ? attackGain : releaseGain;
        rmsBands[i] = (float) (rmsLevel + rmsGain * (rmsBands[i] - rmsLevel));
        final double db = DecibelMeter.amplitudeToDecibels(rmsBands[i]) + gain + bandOctaves[i] * slope;
        final double normalized = LXUtils.clamp(1. + db / range, 0, 1);
        bands[i] = (float) (normalized * FFT_SCALE);
      }
    }

    @Override
    public void onMeterStop(GraphicMeter meter) {
      Arrays.fill(rmsBands, 0f);
      Arrays.fill(bands, 0f);
    }
  };

  // Sensor values

  public float getBandf(int index) {
    return this.bands[index] / FFT_SCALE;
  }

  public void setAccel(int index, short value) {
    if (index < 0 || index >= 3) {
      throw new IllegalArgumentException("Invalid accelerometer index: " + index);
    }
    this.accel[index] = value;
  }

  public void setLight(short value) {
    this.light = value;
  }

  public void setAnalog(int index, short value) {
    if (index < 0 || index >= 5) {
      throw new IllegalArgumentException("Invalid analog index: " + index);
    }
    this.analog[index] = value;
  }

  // Loop

  public void loop() {
    if (this.socket == null) {
      return;
    }
    final long now = this.lx.engine.nowMillis;
    if (now > this.nextSend) {
      sendPacket();
      this.nextSend = now + (long) (1000. / this.fps);
    }
  }

  private void sendPacket() {
    // Bands are populated by the GraphicMeter.Processor at audio frame rate
    float sum = 0;
    float peak = 0;
    int peakIndex = 0;
    for (int i = 0; i < NUM_BANDS; ++i) {
      final float value = this.bands[i];
      sum += value;
      if (value > peak) {
        peak = value;
        peakIndex = i;
      }
    }

    // Header
    this.buffer.rewind();
    this.buffer.putInt(PacketType.SENSOR_BOARD);
    this.buffer.putInt((int) this.lx.engine.nowMillis);
    this.buffer.putInt(0xFFFFFFFF);
    this.buffer.putInt(EXPANSION_TYPE);

    // Frequency Data
    for (int i = 0; i < NUM_BANDS; ++i) {
      this.buffer.putShort((short) this.bands[i]);
    }

    // Audio avg, peak, and peak Hz
    this.buffer.putShort((short) (sum / NUM_BANDS));
    this.buffer.putShort((short) peak);
    this.buffer.putShort((short) (20000 * peakIndex / 512)); // mimics simap/pixelblaze_sensor_board

    // Accelerometer
    for (int i = 0; i < 3; ++i) {
      this.buffer.putShort(this.accel[i]);
    }

    // Light sensor
    this.buffer.putShort(this.light);

    // Analog inputs
    for (int i = 0; i < 5; ++i) {
      this.buffer.putShort(this.analog[i]);
    }

    try {
      this.socket.send(this.packet);
    } catch (Exception e) {
      LOG.error(e, "Failed to send sensor packet");
    }
  }

  public void dispose() {
    setRunning(false);
  }
}
