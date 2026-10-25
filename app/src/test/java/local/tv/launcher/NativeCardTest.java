package local.tv.launcher;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeCardTest {
  private NativeCard progress(long position, long duration) {
    Library.Program program = new Library.Program();
    program.id = "watch:boundary";
    program.title = "Boundary";
    program.owner = "Fixture";
    program.position = position;
    program.duration = duration;
    return NativeCard.program(program, true);
  }

  @Test
  public void playbackProgressUsesOverflowSafeRatiosAcrossValidLongRange() {
    assertEquals(100, progress(Long.MAX_VALUE, Long.MAX_VALUE).progress);
    int half = progress(Long.MAX_VALUE / 2, Long.MAX_VALUE).progress;
    assertTrue(half >= 49 && half <= 50);
    assertEquals(50, progress(30000, 60000).progress);
    assertEquals(0, progress(0, Long.MAX_VALUE).progress);
  }

  @Test
  public void invalidPublishedProgressHasNoInventedPlaybackBar() {
    assertEquals(-1, progress(-1, 60000).progress);
    assertEquals(-1, progress(60001, 60000).progress);
    assertEquals(-1, progress(0, 0).progress);
  }
}
