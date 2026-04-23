/**
 * Copyright 2026- Justin K. Belcher, Heron Arts LLC
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.api;

import heronarts.lx.utils.LXUtils;
import studio.jkb.pb.LOG;

/**
 * Types of user-adjustable controls for Pixelblaze patterns
 */
public enum ControlType {

  SLIDER("slider", true),
  HSV_PICKER("hsvPicker", true),
  RGB_PICKER("rgbPicker", true),
  TOGGLE("toggle", true),
  TRIGGER("trigger", true),
  INPUT_NUMBER("inputNumber", true),
  SHOW_NUMBER("showNumber", false),
  GAUGE("gauge", false);

  public final String prefix;
  public final boolean isInput;

  ControlType(String prefix, boolean isInput) {
    this.prefix = prefix;
    this.isInput = isInput;
  }

  /**
   * Matches a control name to a ControlType using its prefix.
   */
  public static ControlType parse(String name) {
    if (LXUtils.isEmpty(name)) {
      return null;
    }
    for (ControlType ct : values()) {
      if (ct.matches(name)) {
        return ct;
      }
    }
    LOG.error("Invalid control type: " + name);
    return null;
  }

  /**
   * Removes the prefix from a control name.
   */
  public String removePrefix(String name) {
    if (!LXUtils.isEmpty(name) && matches(name)) {
      return name.substring(this.prefix.length());
    }
    return name;
  }

  /**
   * Determine whether a control name matches this type's prefix.
   * String must have already been null-checked.
   */
  private boolean matches(String name) {
    return name.startsWith(this.prefix) && name.length() > this.prefix.length();
  }
}
