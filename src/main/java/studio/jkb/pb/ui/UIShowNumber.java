/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.ui;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.parameter.MutableParameter;

/**
 * Visual representation of a Pixelblaze "showNumber" control
 */
public class UIShowNumber extends UILabel {

  public UIShowNumber(UI ui, float w, float h, MutableParameter parameter) {
    super(0, 0, w, h);
    setBorderColor(ui.theme.controlBorderColor);
    setFont(ui.theme.getControlFont());
    setPadding(0, 4);
    setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE);

    // Four decimals matches the Pixelblaze web UI
    addListener(parameter, p ->
      setLabel(String.format("%.4f", parameter.getValue())),
      true);
  }

}