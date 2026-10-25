package local.tv.launcher;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class TvInputsTest {
  @Test
  public void namedChildReplacesParentAndRetainsPort() {
    var entries =
        TvInputs.map(
            List.of(
                new TvInputs.Input("port", null, "HDMI 2", 0),
                new TvInputs.Input("console", "port", "Game console", 1)));
    assertEquals(1, entries.size());
    assertEquals("console", entries.get(0).id);
    assertEquals("Game console", entries.get(0).label);
    assertEquals("HDMI 2", entries.get(0).portLabel);
    assertEquals("Standby", entries.get(0).status);
  }

  @Test
  public void absentMetadataDoesNotInventDeviceNames() {
    var entries = TvInputs.map(List.of(new TvInputs.Input("port", null, "", -1)));
    assertEquals("HDMI input", entries.get(0).label);
    assertEquals("Status unavailable", entries.get(0).status);
    assertEquals("", entries.get(0).portLabel);
    assertTrue(TvInputs.map(List.of()).isEmpty());
  }

  @Test
  public void unnamedChildKeepsVisiblePortWithoutDuplicate() {
    var entries =
        TvInputs.map(
            List.of(
                new TvInputs.Input("port", null, "HDMI 2", 0),
                new TvInputs.Input("child", "port", "", 0)));
    assertEquals(1, entries.size());
    assertEquals("port", entries.get(0).id);
  }

  @Test
  public void duplicateAndMalformedIdsAreExcluded() {
    var entries =
        TvInputs.map(
            List.of(
                new TvInputs.Input("", null, "Broken", 0),
                new TvInputs.Input("port", null, "HDMI 1", 2),
                new TvInputs.Input("port", null, "Duplicate", 0)));
    assertEquals(1, entries.size());
    assertEquals("Disconnected", entries.get(0).status);
  }
}
