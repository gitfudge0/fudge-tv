package local.tv.launcher;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ImageDiskCacheTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final byte[] COMPLETE = {1, 2, 3, 4};

  private ImageDiskCache cache(long budget) {
    return new ImageDiskCache(
        temporary.getRoot(), 100, budget, bytes -> java.util.Arrays.equals(COMPLETE, bytes));
  }

  @Test
  public void incompleteDiskEntryIsDeletedAndCanBeRegenerated() throws Exception {
    File file = new File(temporary.getRoot(), "art");
    Files.write(file.toPath(), new byte[] {1, 2});
    ImageDiskCache cache = cache(100);
    assertNull(cache.read("art"));
    assertFalse(file.exists());
    cache.write("art", COMPLETE);
    assertArrayEquals(COMPLETE, cache.read("art"));
    Files.write(file.toPath(), new byte[0]);
    assertNull(cache.read("art"));
    cache.write("art", COMPLETE);
    assertArrayEquals(COMPLETE, cache.read("art"));
  }

  @Test
  public void pendingWriteDoesNotReplacePublishedEntryAndSuccessLeavesNoTemporaryFiles()
      throws Exception {
    ImageDiskCache cache = cache(100);
    cache.write("art", COMPLETE);
    File interrupted = new File(temporary.getRoot(), "pending-interrupted.tmp");
    Files.write(interrupted.toPath(), new byte[] {1});
    assertArrayEquals(COMPLETE, cache.read("art"));
    interrupted.delete();
    cache.write("art", COMPLETE);
    assertEquals(1, temporary.getRoot().listFiles().length);
    assertArrayEquals(COMPLETE, cache.read("art"));
  }

  @Test
  public void diskAndEntryBudgetsRemainBounded() throws Exception {
    ImageDiskCache cache = cache(4);
    cache.write("first", COMPLETE);
    assertTrue(new File(temporary.getRoot(), "first").setLastModified(1));
    cache.write("second", COMPLETE);
    assertEquals(1, temporary.getRoot().listFiles().length);
    assertArrayEquals(COMPLETE, cache.read("second"));
    cache.write("oversized", new byte[101]);
    assertFalse(new File(temporary.getRoot(), "oversized").exists());
  }
}
