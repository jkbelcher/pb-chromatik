/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import heronarts.lx.LX;

import studio.jkb.pb.LOG;

/**
 * WebSocket client for a single Pixelblaze device.
 * Connects on port 81 and handles JSON text frames and binary frames.
 */
public class PixelblazeConnection implements WebSocket.Listener {

  public static final boolean FEATURE_SORT_PATTERN_LIST = true;

  public static final int WEBSOCKET_PORT = 81;
  public static final String DEFAULT_PLAYLIST_ID = "_defaultplaylist_";
  private static final byte MSG_TYPE_PROGRAM_LIST = 0x07;
  private static final byte FRAME_FIRST = 0x01;
  private static final byte FRAME_LAST = 0x04;

  public interface Listener {
    public default void onConnected(PixelblazeConnection connection) {}
    public default void onDisconnected(PixelblazeConnection connection) {}
    public default void onError(PixelblazeConnection connection, Throwable error) {}

    public default void onConfig(PixelblazeConnection connection, String name, String version, int pixelCount, double brightness) {}
    public default void onStatus(PixelblazeConnection connection, double fps, int mem, long uptime, long storageUsed, long storageSize) {}

    public default void onSequencerState(PixelblazeConnection connection, SequencerMode mode, boolean running, int playlistPosition) {}
    public default void onSequenceTimer(PixelblazeConnection connection, int seconds) {}

    public default void onPatternList(PixelblazeConnection connection, Map<String, String> patterns) {}
    public default void onPlaylist(PixelblazeConnection connection, List<PlaylistItem> items) {}
    public default void onActivePattern(PixelblazeConnection connection, String patternId) {}

    public default void onControls(PixelblazeConnection connection, Map<String, Object> controls) {}
  }

  private final LX lx;
  private final PixelblazeDevice device;
  private final Listener listener;

  private HttpClient httpClient;
  private WebSocket webSocket;
  private boolean connected = false;
  private volatile boolean sending = false;
  private volatile boolean disconnected = false;

  // WebSocket fragment accumulators
  private ByteBuffer binaryAccumulator;
  private final StringBuilder textAccumulator = new StringBuilder();

  // Pattern list accumulator
  private boolean accumulatingPatternList = false;
  private ByteBuffer patternListAccumulator;

  public PixelblazeConnection(LX lx, PixelblazeDevice device, Listener listener) {
    this.lx = lx;
    this.device = device;
    this.listener = listener;
  }

  public boolean isConnected() {
    return this.connected;
  }

  /**
   * Returns true if a previous send is still in progress. Callers that
   * can retry later (like throttled parameter sends) should skip and
   * try again on the next cycle.
   */
  public boolean isSending() {
    return this.sending;
  }

  // Connection

  public void connect() {
    if (this.httpClient != null) {
      throw new IllegalStateException("Disconnect before starting a new connection");
    }
    this.disconnected = false;
    this.textAccumulator.setLength(0);

    String uri = "ws://" + this.device.address + ":" + WEBSOCKET_PORT;
    LOG.log("Connecting to " + uri);

    this.httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build();
    this.httpClient.newWebSocketBuilder()
      .buildAsync(URI.create(uri), this)
      .whenComplete((ws, error) -> {
        if (error != null) {
          this.lx.engine.addTask(() -> {
            this.listener.onError(this, error);
            this.listener.onDisconnected(this);
          });
        }
      });

    // onOpen() is the async callback...
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    if (this.disconnected) {
      try {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
      } catch (Exception e) {
        // Ignore
      }
      return;
    }

    this.connected = true;
    this.webSocket = webSocket;
    webSocket.request(1);

    this.lx.engine.addTask(() -> this.listener.onConnected(this));

    sendInitialRequest();
  }

  public void disconnect() {
    this.disconnected = true;
    this.connected = false;
    if (this.webSocket != null) {
      try {
        this.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
      } catch (Exception e) {
        // Ignore errors during close
      }
      this.webSocket = null;
    }
    this.httpClient = null;
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    this.connected = false;
    this.webSocket = null;
    this.httpClient = null;
    if (!this.disconnected) {
      this.lx.engine.addTask(() -> this.listener.onDisconnected(this));
    }
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    this.connected = false;
    this.webSocket = null;
    this.httpClient = null;
    if (this.disconnected) {
      return;
    }
    this.lx.engine.addTask(() -> {
      this.listener.onError(this, error);
      this.listener.onDisconnected(this);
    });
  }

  // Sending

  private void sendInitialRequest() {
    sendText("{\"sendUpdates\":false,\"getConfig\":true,\"listPrograms\":true}");
  }

  public void sendActivatePattern(String patternId) {
    sendText("{\"activeProgramId\":\"" + escapeJson(patternId) + "\"}");
  }

  public void sendNextPattern() {
    sendText("{\"nextProgram\":true}");
  }

  public void sendGetPlaylist() {
    sendText("{\"getPlaylist\":\"" + DEFAULT_PLAYLIST_ID + "\"}");
  }

  public void sendPlaylist(List<PlaylistItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"playlist\":{\"id\":\"").append(DEFAULT_PLAYLIST_ID).append("\",\"items\":[");
    for (int i = 0; i < items.size(); ++i) {
      if (i > 0) {
        sb.append(",");
      }
      PlaylistItem item = items.get(i);
      sb.append("{\"id\":\"").append(escapeJson(item.id)).append("\",\"ms\":").append(item.ms).append("}");
    }
    sb.append("]},\"save\":true}");
    sendText(sb.toString());
  }

  public void sendPlaylistPosition(int position) {
    sendText("{\"playlist\":{\"position\":" + position + "}}");
  }

  public void sendSetSequencerMode(int mode) {
    sendText("{\"sequencerMode\":" + mode + "}");
  }

  public void sendRunSequencer(boolean run) {
    sendText("{\"runSequencer\":" + run + "}");
  }

  public void sendSequenceTimer(int seconds) {
    sendText("{\"sequenceTimer\":" + seconds + "}");
  }

  public void sendSetBrightness(double brightness) {
    // Round to 2 decimal places
    double rounded = Math.round(brightness * 100.0) / 100.0;
    sendText("{\"brightness\":" + rounded + "}");
  }

  public void sendGetControls(String patternId) {
    sendText("{\"getControls\":\"" + escapeJson(patternId) + "\"}");
  }

  public void sendSetControl(String name, double value) {
    sendText("{\"setControls\":{\"" + escapeJson(name) + "\":" + value + "},\"save\":false}");
  }

  public void sendSetControlHSV(String name, double h, double s, double v) {
    sendText("{\"setControls\":{\"" + escapeJson(name) + "\":[" + h + "," + s + "," + v + "]},\"save\":false}");
  }

  public void sendSetControlRGB(String name, double r, double g, double b) {
    sendText("{\"setControls\":{\"" + escapeJson(name) + "\":[" + r + "," + g + "," + b + "]},\"save\":false}");
  }

  private void sendText(String text) {
    if (this.webSocket != null && this.connected && !this.disconnected) {
      try {
        this.sending = true;
        this.webSocket.sendText(text, true).whenComplete((ws, error) -> {
          this.sending = false;
          if (error != null) {
            LOG.error(error, "Error sending WebSocket message");
          }
        });
      } catch (Exception e) {
        this.sending = false;
        LOG.error(e, "Error sending WebSocket message");
      }
    }
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // Receiving Text

  private static final String KEY_NAME = "name";
  private static final String KEY_VERSION = "ver";
  private static final String KEY_PIXEL_COUNT = "pixelCount";
  private static final String KEY_BRIGHTNESS = "brightness";
  private static final String KEY_SEQUENCER_MODE = "sequencerMode";
  private static final String KEY_SEQUENCE_TIMER = "sequenceTimer";
  private static final String KEY_RUN_SEQUENCER = "runSequencer";
  private static final String KEY_FPS = "fps";
  private static final String KEY_MEM = "mem";
  private static final String KEY_UPTIME = "uptime";
  private static final String KEY_STORAGE_USED = "storageUsed";
  private static final String KEY_STORAGE_SIZE = "storageSize";
  private static final String KEY_PLAYLIST = "playlist";
  private static final String KEY_PLAYLIST_POSITION = "position";
  private static final String KEY_PLAYLIST_ITEMS = "items";
  private static final String KEY_PLAYLIST_ITEM_ID = "id";
  private static final String KEY_PLAYLIST_ITEM_MS = "ms";
  private static final String KEY_ACTIVE_PROGRAM = "activeProgram";
  private static final String KEY_ACTIVE_PROGRAM_ID = "activeProgramId";
  private static final String KEY_CONTROLS = "controls";

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    webSocket.request(1);
    if (this.disconnected) {
      return null;
    }
    this.textAccumulator.append(data);
    if (last) {
      String message = this.textAccumulator.toString();
      this.textAccumulator.setLength(0);
      processTextMessage(message);
    }
    return null;
  }

  private void processTextMessage(String message) {
    try {
      JsonObject obj = JsonParser.parseString(message).getAsJsonObject();

      // Settings config response: name, brandName, pixelCount, brightness, maxBrightness, colorOrder, dataSpeed, ledType, sequenceTimer, transitionDuration, sequencerMode, runSequencer, simpleUiMode, learningUiMode, discoveryEnable, timezone, autoOffEnable, autoOffStart, autoOffEnd, cpuSpeed, networkPowerSave, mapperFit, leaderId, nodeId, soundSrc, accelSrc, lightSrc, analogSrc, exp, ver, chipId
      if (obj.has(KEY_NAME) && obj.has(KEY_VERSION)) {
        String name = obj.get(KEY_NAME).getAsString();
        String version = obj.get(KEY_VERSION).getAsString();
        int pixelCount = obj.has(KEY_PIXEL_COUNT) ? obj.get(KEY_PIXEL_COUNT).getAsInt() : 0;
        double brightness = obj.get(KEY_BRIGHTNESS).getAsDouble();
        this.lx.engine.addTask(() -> this.listener.onConfig(this, name, version, pixelCount, brightness));
      }

      // Sequencer state
      if (obj.has(KEY_SEQUENCER_MODE)) {
        int modeInt = obj.get(KEY_SEQUENCER_MODE).getAsInt();
        SequencerMode mode = SequencerMode.valueOf(modeInt);
        boolean running = obj.has(KEY_RUN_SEQUENCER) && obj.get(KEY_RUN_SEQUENCER).getAsBoolean();
        int position = -1;
        if (obj.has(KEY_PLAYLIST) && obj.getAsJsonObject(KEY_PLAYLIST).has(KEY_PLAYLIST_POSITION)) {
          position = obj.getAsJsonObject(KEY_PLAYLIST).get(KEY_PLAYLIST_POSITION).getAsInt();
        }
        final int pos = position;
        this.lx.engine.addTask(() -> this.listener.onSequencerState(this, mode, running, pos));
      }

      // Sequence timer
      if (obj.has(KEY_SEQUENCE_TIMER)) {
        int seconds = obj.get(KEY_SEQUENCE_TIMER).getAsInt();
        this.lx.engine.addTask(() -> this.listener.onSequenceTimer(this, seconds));
      }

      // Status: fps, vmerr, vmerrpc, mem, exp, renderType, uptime, storageUsed, storageSize, rr0, rr1, rebootCounter
      if (obj.has(KEY_FPS)) {
        double fps = obj.get(KEY_FPS).getAsDouble();
        int mem = obj.has(KEY_MEM) ? obj.get(KEY_MEM).getAsInt() : 0;
        long uptime = obj.has(KEY_UPTIME) ? obj.get(KEY_UPTIME).getAsLong() : 0;
        long storageUsed = obj.has(KEY_STORAGE_USED) ? obj.get(KEY_STORAGE_USED).getAsLong() : 0;
        long storageSize = obj.has(KEY_STORAGE_SIZE) ? obj.get(KEY_STORAGE_SIZE).getAsLong() : 0;
        this.lx.engine.addTask(() -> this.listener.onStatus(this, fps, mem, uptime, storageUsed, storageSize));
        return;
      }

      // Playlist contents (response to getPlaylist). Distinguished from the
      // sequencer-state playlist field above by the presence of "items".
      if (obj.has(KEY_PLAYLIST) && obj.getAsJsonObject(KEY_PLAYLIST).has(KEY_PLAYLIST_ITEMS)) {
        JsonArray itemsArr = obj.getAsJsonObject(KEY_PLAYLIST).getAsJsonArray(KEY_PLAYLIST_ITEMS);
        final List<PlaylistItem> items = new ArrayList<>(itemsArr.size());
        for (JsonElement el : itemsArr) {
          JsonObject itemObj = el.getAsJsonObject();
          String id = itemObj.get(KEY_PLAYLIST_ITEM_ID).getAsString();
          int ms = itemObj.has(KEY_PLAYLIST_ITEM_MS) ? itemObj.get(KEY_PLAYLIST_ITEM_MS).getAsInt() : 0;
          items.add(new PlaylistItem(id, ms));
        }
        this.lx.engine.addTask(() -> this.listener.onPlaylist(this, items));
      }

      // Active pattern
      if (obj.has(KEY_ACTIVE_PROGRAM)) {
        JsonObject activeProgram = obj.getAsJsonObject(KEY_ACTIVE_PROGRAM);
        if (activeProgram.has(KEY_ACTIVE_PROGRAM_ID)) {
          String patternId = activeProgram.get(KEY_ACTIVE_PROGRAM_ID).getAsString();
          this.lx.engine.addTask(() -> this.listener.onActivePattern(this, patternId));
        }
      }

      // Pattern controls
      if (obj.has(KEY_CONTROLS)) {
        processControls(obj.getAsJsonObject(KEY_CONTROLS));
      }

    } catch (Exception e) {
      LOG.error(e, "Error parsing WebSocket text message");
    }
  }

  private void processControls(JsonObject controlsWrapper) {
    Map<String, Object> results = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> patternEntry : controlsWrapper.entrySet()) {
      // Note(JKB): This assumes only one pattern's controls are returned
      String patternId = patternEntry.getKey(); // Keep for reference

      JsonElement patternElem = patternEntry.getValue();
      if (patternElem.isJsonObject()) {

        // Controls within a pattern
        JsonObject patternControls = patternElem.getAsJsonObject();
        for (Map.Entry<String, JsonElement> patternControl : patternControls.entrySet()) {
          String controlName = patternControl.getKey();
          JsonElement controlValue = patternControl.getValue();

          if (controlValue.isJsonPrimitive()) {
            // Slider, inputNumber, showNumber, gauge, toggle, trigger
            if (controlValue.getAsJsonPrimitive().isNumber()) {
              results.put(controlName, controlValue.getAsDouble());
            } else if (controlValue.getAsJsonPrimitive().isBoolean()) {
              results.put(controlName, controlValue.getAsBoolean());
            }
          } else if (controlValue.isJsonArray()) {
            // Color control
            JsonArray arr = controlValue.getAsJsonArray();
            double[] values = new double[arr.size()];
            for (int i = 0; i < arr.size(); ++i) {
              values[i] = arr.get(i).getAsDouble();
            }
            results.put(controlName, values);
          }
        }
      }
    }

    this.lx.engine.addTask(() -> this.listener.onControls(this, results));
  }

  // Receiving Binary

  @Override
  public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    webSocket.request(1);
    if (this.disconnected) {
      return null;
    }
    if (!last) {
      // Accumulate WebSocket-level fragments until the message is complete
      if (this.binaryAccumulator == null) {
        this.binaryAccumulator = ByteBuffer.allocate(data.remaining() + 4096);
      }
      if (this.binaryAccumulator.remaining() < data.remaining()) {
        ByteBuffer expanded = ByteBuffer.allocate(this.binaryAccumulator.capacity() + data.remaining() + 4096);
        this.binaryAccumulator.flip();
        expanded.put(this.binaryAccumulator);
        this.binaryAccumulator = expanded;
      }
      this.binaryAccumulator.put(data);
    } else if (this.binaryAccumulator != null) {
      // Final fragment — combine with accumulated data
      if (this.binaryAccumulator.remaining() < data.remaining()) {
        ByteBuffer expanded = ByteBuffer.allocate(this.binaryAccumulator.capacity() + data.remaining() + 4096);
        this.binaryAccumulator.flip();
        expanded.put(this.binaryAccumulator);
        this.binaryAccumulator = expanded;
      }
      this.binaryAccumulator.put(data);
      this.binaryAccumulator.flip();
      processBinaryData(this.binaryAccumulator);
      this.binaryAccumulator = null;
    } else {
      // Complete message in a single frame
      processBinaryData(data);
    }
    return null;
  }

  private void processBinaryData(ByteBuffer data) {
    if (!data.hasRemaining()) {
      return;
    }

    byte msgType = data.get();

    if (msgType == MSG_TYPE_PROGRAM_LIST) {
      if (!data.hasRemaining()) {
        return;
      }
      byte flags = data.get();

      if ((flags & FRAME_FIRST) != 0) {
        this.patternListAccumulator = ByteBuffer.allocate(4096);
        this.accumulatingPatternList = true;
      }

      if (this.accumulatingPatternList && data.hasRemaining()) {
        // Expand accumulator if needed
        if (this.patternListAccumulator.remaining() < data.remaining()) {
          ByteBuffer expanded = ByteBuffer.allocate(this.patternListAccumulator.capacity() + data.remaining() + 4096);
          this.patternListAccumulator.flip();
          expanded.put(this.patternListAccumulator);
          this.patternListAccumulator = expanded;
        }
        this.patternListAccumulator.put(data);
      }

      if ((flags & FRAME_LAST) != 0 && this.accumulatingPatternList) {
        this.patternListAccumulator.flip();
        String patternData = StandardCharsets.UTF_8.decode(this.patternListAccumulator).toString();
        parsePatternList(patternData);
        this.accumulatingPatternList = false;
        this.patternListAccumulator = null;
      }
    }
    // Ignore other binary message types (preview frames, etc.)
  }

  private void parsePatternList(String data) {
    Map<String, String> patterns = new LinkedHashMap<>();
    for (String line : data.split("\n")) {
      String[] parts = line.split("\t", 2);
      if (parts.length == 2) {
        String id = parts[0].trim();
        String name = parts[1].trim();
        if (!id.isEmpty() && !name.isEmpty()) {
          patterns.put(id, name);
        }
      }
    }

    // Sorting might be better at the UI level, but it's easy to do here.
    if (FEATURE_SORT_PATTERN_LIST) {
      Map<String, String> sorted = new LinkedHashMap<>();
      patterns.entrySet().stream()
              .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
              .forEach(e -> sorted.put(e.getKey(), e.getValue()));
      patterns = sorted;
    }

    // Pass to engine thread
    final Map<String, String> finalPatterns = patterns;
    this.lx.engine.addTask(() -> this.listener.onPatternList(this, finalPatterns));

    // Request playlist now that we have the patternIds
    sendGetPlaylist();
  }

  /**
   * A pattern entry in a Pixelblaze playlist
   */
  public static class PlaylistItem {
    public final String id;
    public final int ms;

    public PlaylistItem(String id, int ms) {
      this.id = id;
      this.ms = ms;
    }
  }

}
