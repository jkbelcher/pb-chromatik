/**
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.api;

/**
 * Pixelblaze sequencer modes
 */
public enum SequencerMode {

  OFF("Off"),
  SHUFFLE_ALL("Shuffle All"),
  PLAYLIST("Playlist");

  public final String label;

  SequencerMode(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return this.label;
  }

  public static SequencerMode valueOf(int mode) {
    if (mode < 0 || mode >= values().length) {
      return null;
    }
    return values()[mode];
  }
}
