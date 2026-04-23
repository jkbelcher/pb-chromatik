/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb.ui;

import heronarts.glx.event.KeyEvent;
import heronarts.glx.event.MouseEvent;
import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UICollapsibleSection;
import heronarts.glx.ui.component.UIDropMenu;
import heronarts.glx.ui.component.UIIndicator;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.vg.VGraphics;

import studio.jkb.pb.PixelblazeComponent;
import studio.jkb.pb.api.PixelblazeDevice;

/**
 * Main UI for the Pixelblaze plugin
 */
public class UIPixelblazeComponent extends UICollapsibleSection {

  private static final float HORIZONTAL_SPACING = 4;
  private static final float ROW_HEIGHT = 16;
  private static final float DEVICE_LABEL_WIDTH = 40;
  private static final float INDICATOR_SIZE = 8;
  private static final float INDICATOR_Y = (ROW_HEIGHT - INDICATOR_SIZE) / 2;
  private static final float INFO_BUTTON_SIZE = 12;
  private static final float INFO_BUTTON_Y = (ROW_HEIGHT - INFO_BUTTON_SIZE) / 2;

  private final UI ui;
  private final PixelblazeComponent pb;

  private final UI2dContainer deviceSlot;
  private final UIInfoButton infoButton;

  private final UIPixelblazeDevice deviceUI;
  private final PixelblazeComponent.Listener pbListener;

  public UIPixelblazeComponent(UI ui, PixelblazeComponent pb, float w) {
    super(ui, 0, 0, w, 0);
    this.ui = ui;
    this.pb = pb;
    setTitle("PIXELBLAZE");
    setLayout(Layout.VERTICAL, 4);
    setPadding(2, 0);

    final float contentWidth = getContentWidth();
    final float deviceSelectorWidth = contentWidth - DEVICE_LABEL_WIDTH - INDICATOR_SIZE - INFO_BUTTON_SIZE - (3 * HORIZONTAL_SPACING);

    addTopLevelComponent(
      new UIButton.Toggle(w - PADDING - UIButton.Toggle.SIZE, PADDING, pb.enabled)
    );

    this.deviceUI = new UIPixelblazeDevice(ui, contentWidth);

    this.infoButton = new UIInfoButton(ui, 0, INFO_BUTTON_Y, INFO_BUTTON_SIZE, this.deviceUI::getStatsOverlay);

    // Only show info button when a device is connected
    addListener(pb.connected, p -> infoButton.setVisible(pb.connected.isOn()), true);

    // Slot container that holds the per-device subview (rebuilt on active device change)
    this.deviceSlot = new UI2dContainer(0, 0, contentWidth, 0)
      .setLayout(Layout.VERTICAL);

    addChildren(
      // Device row
      UI2dContainer.newHorizontalContainer(ROW_HEIGHT, HORIZONTAL_SPACING,
        new UILabel.Control(ui, DEVICE_LABEL_WIDTH, ROW_HEIGHT, "Device"),
        new UIDropMenu(deviceSelectorWidth, ROW_HEIGHT, pb.deviceSelector),
        new UIIndicator(ui, 0, INDICATOR_Y, INDICATOR_SIZE, INDICATOR_SIZE, pb.connected)
          .setClickable(false),
        infoButton
      ),

      // Current device slot
      this.deviceSlot
    );

    this.deviceUI.addToContainer(this.deviceSlot);

    pb.addListener(this.pbListener = new PixelblazeComponent.Listener() {
      @Override
      public void onActiveDeviceChanged(PixelblazeComponent pb, PixelblazeDevice device) {
        deviceUI.setDevice(device);
      }
    });
    this.deviceUI.setDevice(pb.getActiveDevice());
  }

  @Override
  public void dispose() {
    this.pb.removeListener(this.pbListener);
    super.dispose();
  }

  /**
   * Round info button that shows a stats overlay when clicked.
   */
  private static class UIInfoButton extends UI2dComponent {

    private final UI ui;
    private final java.util.function.Supplier<UI2dComponent> overlaySupplier;
    private boolean mouseDown = false;

    UIInfoButton(UI ui, float x, float y, float size, java.util.function.Supplier<UI2dComponent> overlaySupplier) {
      super(x, y, size, size);
      this.ui = ui;
      this.overlaySupplier = overlaySupplier;
      setBorderColor(ui.theme.controlTextColor);
      setFontColor(ui.theme.controlTextColor);
      setBorderRounding((int) Math.ceil(size / 2));
    }

    @Override
    protected void onDraw(UI ui, VGraphics vg) {
      // Draw "i" centered in the circle
      vg.fillColor(this.mouseDown ? ui.theme.controlActiveTextColor : getFontColor());
      vg.fontFace(ui.theme.getControlFont());
      vg.fontSize(8);
      vg.beginPath();
      vg.textAlign(VGraphics.Align.CENTER, VGraphics.Align.MIDDLE);
      vg.text(this.width / 2, (this.height / 2) + 1, "i");
      vg.fill();
    }

    @Override
    public void onMousePressed(MouseEvent mouseEvent, float mx, float my) {
      mouseEvent.consume();
      this.mouseDown = true;
      showPopup();
      redraw();
    }

    @Override
    public void onMouseReleased(MouseEvent mouseEvent, float mx, float my) {
      this.mouseDown = false;
      redraw();
    }

    @Override
    public void onKeyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
      UI2dComponent overlay = this.overlaySupplier.get();
      if (overlay != null && overlay.isVisible()) {
        keyEvent.consume();
      } else if ((keyCode == KeyEvent.VK_SPACE) || keyEvent.isEnter()) {
        keyEvent.consume();
        showPopup();
      }
    }

    private void showPopup() {
      UI2dComponent overlay = this.overlaySupplier.get();
      if (overlay != null) {
        this.ui.showContextOverlay(overlay, this, UI.Position.BOTTOM_RIGHT, UI.Position.TOP_RIGHT);
      }
    }

    @Override
    public void dispose() {
      UI2dComponent overlay = this.overlaySupplier.get();
      if (overlay != null) {
        this.ui.clearContextOverlay(overlay);
      }
      super.dispose();
    }
  }
}
