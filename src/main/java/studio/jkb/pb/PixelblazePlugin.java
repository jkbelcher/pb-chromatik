/**
 * Copyright 2026- Justin K. Belcher
 * @author Justin K. Belcher <justin@jkb.studio>
 */

package studio.jkb.pb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import heronarts.lx.LX;
import heronarts.lx.LXPlugin;
import heronarts.lx.studio.LXStudio;

import studio.jkb.pb.ui.UIPixelblazeComponent;

@LXPlugin.Name("Pixelblaze")
public class PixelblazePlugin implements LXStudio.Plugin {

  private PixelblazeComponent pb;

  public PixelblazePlugin(LX lx) {
    LOG.log(getClass().getSimpleName() + "(LX) version: " + loadVersion());
  }

  @Override
  public void initialize(LX lx) {
    lx.engine.registerComponent("pb", this.pb = new PixelblazeComponent(lx));
  }

  @Override
  public void initializeUI(LXStudio lxStudio, LXStudio.UI ui) {}

  @Override
  public void onUIReady(LXStudio lxStudio, LXStudio.UI ui) {
    new UIPixelblazeComponent(ui, this.pb, ui.leftPane.global.getContentWidth())
      .addBeforeSibling(ui.leftPane.palette);
  }

  @Override
  public void dispose() {
    this.pb.dispose();
  }

  /**
   * Loads 'pb-chromatik.properties', after maven resource filtering has been applied.
   */
  private String loadVersion() {
    String version = "";
    Properties properties = new Properties();
    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("pb-chromatik.properties")) {
      properties.load(inputStream);
      version = properties.getProperty("pb-chromatik.version");
    } catch (IOException e) {
      LOG.error("Failed to load version information " + e);
    }
    return version;
  }
}
