package local.tv.launcher;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.*;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Injected dispatch tests never send initialization broadcasts to installed third-party apps. */
@RunWith(AndroidJUnit4.class)
public class RecommendationInitializationTest {
  private Context context;
  private SharedPreferences state, preferences;
  private Map<String, ?> savedState;
  private boolean originallyEnabled;
  private boolean hadEnabled;
  private final String owner = "fixture.publisher";

  @Before
  public void prepare() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    state = context.getSharedPreferences("recommendation_initialization", Context.MODE_PRIVATE);
    preferences = context.getSharedPreferences("recommendations", Context.MODE_PRIVATE);
    savedState = new HashMap<>(state.getAll());
    hadEnabled = preferences.contains("enabled");
    originallyEnabled = preferences.getBoolean("enabled", true);
    state.edit().clear().commit();
    preferences.edit().putBoolean("enabled", true).commit();
  }

  @After
  public void restore() {
    SharedPreferences.Editor edit = state.edit().clear();
    for (Map.Entry<String, ?> item : savedState.entrySet())
      edit.putString(item.getKey(), (String) item.getValue());
    edit.commit();
    if (hadEnabled) preferences.edit().putBoolean("enabled", originallyEnabled).commit();
    else preferences.edit().remove("enabled").commit();
  }

  private final class Fixture implements RecommendationInitialization.Source {
    long version = 1, updated = 10;
    int attempts;
    String failing = "";
    final List<ResolveInfo> receivers = new ArrayList<>();
    final List<Intent> sent = new ArrayList<>();

    public PackageInfo packageInfo(String requestedOwner) {
      assertEquals(owner, requestedOwner);
      PackageInfo info = new PackageInfo();
      info.setLongVersionCode(version);
      info.lastUpdateTime = updated;
      return info;
    }

    public List<ResolveInfo> receivers(Intent request) {
      assertEquals(TvContract.ACTION_INITIALIZE_PROGRAMS, request.getAction());
      assertEquals(owner, request.getPackage());
      return receivers;
    }

    public boolean permitted(String permission) {
      return false;
    }

    public void send(Intent request) {
      attempts++;
      assertNotNull(request.getComponent());
      assertEquals(owner, request.getComponent().getPackageName());
      assertNull(request.getExtras());
      assertEquals(0, request.getFlags());
      if (request.getComponent().getClassName().equals(failing)) throw new SecurityException();
      sent.add(request);
    }

    ResolveInfo add(String name) {
      ResolveInfo result = new ResolveInfo();
      ActivityInfo activity = result.activityInfo = new ActivityInfo();
      activity.packageName = owner;
      activity.name = name;
      activity.enabled = activity.exported = true;
      activity.applicationInfo = new ApplicationInfo();
      activity.applicationInfo.enabled = true;
      receivers.add(result);
      return result;
    }

    void run() {
      RecommendationInitialization.initialize(
          context,
          Arrays.asList(
              new Library.App(owner, "Fixture", null), new Library.App(owner, "Duplicate", null)),
          null,
          this);
    }
  }

  @Test
  public void browserPublisherIsSkippedBeforePackageInspection() {
    Fixture fixture = new Fixture();
    fixture.add("Receiver");
    RecommendationInitialization.initialize(
        context,
        Arrays.asList(
            new Library.App("com.vewd.core.integration.dia", "Browser", null),
            new Library.App(owner, "Fixture", null)),
        null,
        fixture);
    assertEquals(1, fixture.sent.size());
    assertEquals(owner, fixture.sent.get(0).getPackage());
  }

  @Test
  public void dispatchIsExplicitOncePerReceiverAndVersion() {
    Fixture fixture = new Fixture();
    fixture.add("First");
    fixture.add("Second");
    fixture.run();
    fixture.run();
    assertEquals(2, fixture.sent.size());
    fixture.version++;
    fixture.run();
    assertEquals(4, fixture.sent.size());
    fixture.updated++;
    fixture.run();
    assertEquals(6, fixture.sent.size());
  }

  @Test
  public void restrictedDisabledAndForeignReceiversAreSkipped() {
    Fixture fixture = new Fixture();
    fixture.add("Private").activityInfo.exported = false;
    fixture.add("Disabled").activityInfo.enabled = false;
    fixture.add("DisabledApp").activityInfo.applicationInfo.enabled = false;
    fixture.add("Protected").activityInfo.permission = "fixture.signature.permission";
    fixture.add("Foreign").activityInfo.packageName = "other.publisher";
    fixture.add("Allowed");
    fixture.run();
    assertEquals(1, fixture.sent.size());
    assertEquals("Allowed", fixture.sent.get(0).getComponent().getClassName());
  }

  @Test
  public void failedReceiverRetriesWithoutRepeatingSuccessfulReceiver() {
    Fixture fixture = new Fixture();
    fixture.add("Failing");
    fixture.add("Healthy");
    fixture.failing = "Failing";
    fixture.run();
    assertEquals(1, fixture.sent.size());
    fixture.failing = "";
    fixture.run();
    fixture.run();
    assertEquals(3, fixture.attempts);
    assertEquals(2, fixture.sent.size());
  }

  @Test
  public void disabledRecommendationsAndCancellationDoNotDispatch() {
    Fixture fixture = new Fixture();
    fixture.add("Receiver");
    preferences.edit().putBoolean("enabled", false).commit();
    fixture.run();
    assertEquals(0, fixture.attempts);
    preferences.edit().putBoolean("enabled", true).commit();
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    try {
      RecommendationInitialization.initialize(
          context,
          Collections.singletonList(new Library.App(owner, "Fixture", null)),
          signal,
          fixture);
      fail("Expected cancellation");
    } catch (OperationCanceledException expected) {
      assertEquals(0, fixture.attempts);
    }
  }
}
