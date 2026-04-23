/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.api;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import heronarts.lx.LX;
import heronarts.lx.utils.ObservableList;

import studio.jkb.pb.LOG;

/**
 * Listens for Pixelblaze beacon packets on UDP port 1889.
 * Beacons are 12 bytes: three little-endian uint32 values
 * (packetType=42, ipv4Address, currentTimeMs).
 */
public class PixelblazeDiscovery {

  public static final int DISCOVERY_PORT = 1889;
  private static final int BEACON_TYPE = 42;
  private static final int BEACON_LENGTH = 12;

  private static final long STALE_TIMEOUT_MS = 5000;
  private static final double STALE_CHECK_INTERVAL_MS = 2000;
  private double staleCheckAccumulator = 0;

  public interface Listener {
    public void onDeviceDiscovered(PixelblazeDevice device);
    public void onDeviceExpired(PixelblazeDevice device);
  }

  private final LX lx;
  private final Listener listener;
  private Thread thread;
  private DatagramSocket socket;
  private volatile boolean running = false;

  private final Map<String, PixelblazeDevice> deviceMap = new HashMap<>();
  private final ObservableList<PixelblazeDevice> mutableDevices = new ObservableList<>();
  public final ObservableList<PixelblazeDevice> devices = this.mutableDevices.asUnmodifiableList();

  public PixelblazeDiscovery(LX lx, Listener listener) {
    this.lx = lx;
    this.listener = Objects.requireNonNull(listener);
  }

  public void start() {
    if (this.running) {
      return;
    }
    this.running = true;
    this.thread = new Thread(this::run, "Pixelblaze-Discovery");
    this.thread.setDaemon(true);
    this.thread.start();
    LOG.log("Discovery started on port " + DISCOVERY_PORT);
  }

  public void stop() {
    this.running = false;
    if (this.socket != null) {
      this.socket.close();
    }
    if (this.thread != null) {
      this.thread.interrupt();
      this.thread = null;
    }
    clearDevices();
    LOG.log("Discovery stopped");
  }

  private void clearDevices() {
    List<PixelblazeDevice> removed = new ArrayList<>(this.devices);
    this.deviceMap.clear();
    for (PixelblazeDevice device : removed) {
      device.dispose();
    }
  }

  private void run() {
    try {
      this.socket = new DatagramSocket(DISCOVERY_PORT);
      this.socket.setReuseAddress(true);
    } catch (SocketException e) {
      LOG.error(e, "Failed to bind UDP port " + DISCOVERY_PORT);
      this.running = false;
      return;
    }

    byte[] buffer = new byte[64];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

    while (this.running) {
      try {
        this.socket.receive(packet);
        processPacket(packet);
      } catch (SocketException e) {
        if (this.running) {
          LOG.error(e, "Socket error during discovery");
        }
        // else: socket closed during stop(), exit quietly
      } catch (Exception e) {
        if (this.running) {
          LOG.error(e, "Error receiving discovery packet");
        }
      }
    }

    if (this.socket != null && !this.socket.isClosed()) {
      this.socket.close();
    }
  }

  private void processPacket(DatagramPacket packet) {
    if (packet.getLength() < BEACON_LENGTH) {
      return;
    }

    ByteBuffer byteBuffer = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

    int packetType = byteBuffer.getInt();
    if (packetType != BEACON_TYPE) {
      return;
    }

    // Use the UDP datagram source address, not the beacon payload IP.
    // The payload IP is the device's self-reported address which may not
    // be routable (e.g. address DHCP'd from AP - ask me how I know)
    InetAddress inetAddress = packet.getAddress();
    String address = inetAddress.getHostAddress();
    this.lx.engine.addTask(() -> handleBeacon(address));
  }

  private void handleBeacon(String address) {
    PixelblazeDevice existing = this.deviceMap.get(address);
    if (existing != null) {
      existing.touch();
      return;
    }
    PixelblazeDevice device = new PixelblazeDevice(this.lx, address);
    this.deviceMap.put(address, device);
    this.listener.onDeviceDiscovered(device);
  }

  public void loop(double deltaMs) {
    // Expire stale devices
    this.staleCheckAccumulator += deltaMs;
    if (this.staleCheckAccumulator < STALE_CHECK_INTERVAL_MS) {
      return;
    }
    this.staleCheckAccumulator = 0;
    final long now = System.currentTimeMillis();
    List<PixelblazeDevice> expired = new ArrayList<>();
    for (PixelblazeDevice device : this.devices) {
      if (now - device.getLastSeen() > STALE_TIMEOUT_MS) {
        expired.add(device);
      }
    }
    for (PixelblazeDevice device : expired) {
      this.deviceMap.remove(device.address);
      this.mutableDevices.remove(device);
      this.listener.onDeviceExpired(device);
      device.dispose();
    }
  }
}
