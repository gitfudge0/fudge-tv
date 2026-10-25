package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real bitmap/target integration without network or package/device mutations. */
@RunWith(AndroidJUnit4.class)
public class NativeImagesTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private final List<NativeImages> loaders = new ArrayList<>();
  private File directory;

  @Before
  public void prepare() {
    directory = new File(context.getCacheDir(), "image-test-" + UUID.randomUUID());
    assertTrue(directory.mkdirs());
  }

  @After
  public void cleanup() {
    instrumentation.runOnMainSync(
        () -> {
          for (NativeImages images : loaders) images.close();
        });
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) file.delete();
    directory.delete();
  }

  private NativeImages loader(NativeImages.Source source) {
    NativeImages images = new NativeImages(context, directory, source);
    loaders.add(images);
    return images;
  }

  private static byte[] jpeg(int color) {
    Bitmap bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.RGB_565);
    bitmap.eraseColor(color);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
    bitmap.recycle();
    return out.toByteArray();
  }

  private final class Target extends ImageView {
    final CountDownLatch delivered = new CountDownLatch(1);
    Bitmap bitmap;

    Target() {
      super(context);
    }

    @Override
    public void setImageBitmap(Bitmap image) {
      super.setImageBitmap(image);
      bitmap = image;
      if (image != null) delivered.countDown();
    }
  }

  @Test
  public void identicalTargetsShareOneDecodeAndTruncatedDiskEntryRefetches() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch sourceStarted = new CountDownLatch(1), release = new CountDownLatch(1);
    byte[] bytes = jpeg(Color.RED);
    NativeImages images =
        loader(
            uri -> {
              calls.incrementAndGet();
              sourceStarted.countDown();
              try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout");
              } catch (InterruptedException canceled) {
                throw new InterruptedIOException();
              }
              return bytes;
            });
    Target[] targets = new Target[2];
    instrumentation.runOnMainSync(
        () -> {
          targets[0] = new Target();
          targets[1] = new Target();
          images.load(targets[0], "https://example.test/shared.jpg", 64, 36);
          images.load(targets[1], "https://example.test/shared.jpg", 64, 36);
        });
    try {
      assertTrue(sourceStarted.await(3, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
    }
    assertTrue(targets[0].delivered.await(3, TimeUnit.SECONDS));
    assertTrue(targets[1].delivered.await(3, TimeUnit.SECONDS));
    instrumentation.runOnMainSync(images::close);
    File[] files = directory.listFiles();
    assertNotNull(files);
    assertEquals(1, files.length);
    Files.write(files[0].toPath(), Arrays.copyOf(bytes, bytes.length / 2));
    NativeImages recovered =
        loader(
            uri -> {
              calls.incrementAndGet();
              return bytes;
            });
    Target[] replacement = new Target[1];
    instrumentation.runOnMainSync(
        () -> {
          replacement[0] = new Target();
          recovered.load(replacement[0], "https://example.test/shared.jpg", 64, 36);
        });
    assertTrue(replacement[0].delivered.await(3, TimeUnit.SECONDS));
    assertEquals(2, calls.get());
    assertTrue(NativeImages.validJpeg(Files.readAllBytes(files[0].toPath())));
  }

  @Test
  public void recycledTargetCannotReceiveOldCompletionAndCloseCancelsFurtherLoads()
      throws Exception {
    CountDownLatch oldStarted = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
    NativeImages images =
        loader(
            uri -> {
              if (uri.contains("old")) {
                oldStarted.countDown();
                boolean released = false;
                while (!released) {
                  try {
                    released = releaseOld.await(3, TimeUnit.SECONDS);
                  } catch (InterruptedException ignored) {
                    /* Simulate a provider finishing after cancellation. */
                  }
                }
                return jpeg(Color.RED);
              }
              return jpeg(Color.BLUE);
            });
    Target[] target = new Target[1];
    instrumentation.runOnMainSync(
        () -> {
          target[0] = new Target();
          images.load(target[0], "https://example.test/old.jpg", 64, 36);
        });
    assertTrue(oldStarted.await(3, TimeUnit.SECONDS));
    instrumentation.runOnMainSync(
        () -> images.load(target[0], "https://example.test/new.jpg", 64, 36));
    try {
      assertTrue(target[0].delivered.await(3, TimeUnit.SECONDS));
    } finally {
      releaseOld.countDown();
    }
    instrumentation.waitForIdleSync();
    instrumentation.runOnMainSync(
        () -> {
          int color = target[0].bitmap.getPixel(32, 18);
          assertTrue(Color.blue(color) > 200);
          assertTrue(Color.red(color) < 30);
          images.close();
          Bitmap current = target[0].bitmap;
          images.load(target[0], "https://example.test/after-close.jpg", 64, 36);
          assertSame(current, target[0].bitmap);
        });
  }

  @Test
  public void appArtworkComesFromCurrentSnapshotRatherThanPersistentPackageCache() {
    NativeImages images =
        loader(
            uri -> {
              throw new AssertionError("App art must not fetch");
            });
    instrumentation.runOnMainSync(
        () -> {
          ImageView target = new ImageView(context);
          Library.App app = new Library.App("tv.example", "Example", null);
          app.banner = new ColorDrawable(Color.RED);
          images.showApp(target, app, false);
          assertEquals(Color.RED, ((ColorDrawable) target.getDrawable()).getColor());
          app.banner = new ColorDrawable(Color.BLUE);
          images.showApp(target, app, false);
          assertEquals(Color.BLUE, ((ColorDrawable) target.getDrawable()).getColor());
        });
    assertEquals(0, directory.listFiles().length);
  }
}
