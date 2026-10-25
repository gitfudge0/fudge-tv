package local.tv.launcher;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opens the actual native dialogs and activates the accessibility action, not an adapter callback.
 */
@RunWith(AndroidJUnit4.class)
public class NativeSettingsTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

  @Test
  public void recommendationsExposeExplanationAndClickablePersistedSwitchTogether() {
    NativeLifecycleTestActivity.fixture = new NativeLifecycleTestActivity.Fixture();
    Intent intent =
        new Intent(instrumentation.getTargetContext(), NativeLifecycleTestActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    NativeLifecycleTestActivity activity = null;
    SharedPreferences preferences = null;
    boolean originallyPresent = false, original = true;
    Integer originalAccessibilityFlags = null;
    try {
      AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
      assertNotNull("The accessibility service must be connected", service);
      originalAccessibilityFlags = service.flags;
      service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
      instrumentation.getUiAutomation().setServiceInfo(service);
      activity = (NativeLifecycleTestActivity) instrumentation.startActivitySync(intent);
      preferences =
          activity.getSharedPreferences("recommendations", android.content.Context.MODE_PRIVATE);
      originallyPresent = preferences.contains("enabled");
      original = preferences.getBoolean("enabled", true);
      NativeLifecycleTestActivity current = activity;
      instrumentation.runOnMainSync(current::onControls);
      instrumentation.waitForIdleSync();
      clickScrollableDialogText("TV controls", "Recommendations");
      String initialLabel = "Recommendations: " + (original ? "On" : "Off");
      AccessibilityNodeInfo toggle = awaitVisibleText(initialLabel, true);
      assertNotNull(
          "The recommendation action list must be attached beside the explanation", toggle);
      assertNotNull(
          "Permission boundary must remain visible",
          awaitVisibleText(
              "Only content this TV provider permits the launcher to read is shown. TV listings"
                  + " permission does not guarantee access to other apps’ previews or watch"
                  + " history.",
              true));
      clickText(initialLabel);
      SharedPreferences saved = preferences;
      boolean expected = !original;
      long deadline = SystemClock.uptimeMillis() + 3000;
      while (saved.getBoolean("enabled", true) != expected && SystemClock.uptimeMillis() < deadline)
        SystemClock.sleep(25);
      assertEquals(
          "Clicking the actual dialog row must persist the preference",
          expected,
          saved.getBoolean("enabled", true));
      assertNotNull(
          "Redisplayed dialog must expose the new value",
          awaitVisibleText("Recommendations: " + (expected ? "On" : "Off"), true));
      clickReadyTarget("android:id/button2", true);
      awaitRecommendationDialogDismissed(current, "Recommendations: " + (expected ? "On" : "Off"));
      instrumentation.runOnMainSync(current::onControls);
      instrumentation.waitForIdleSync();
      clickScrollableDialogText("TV controls", "Recommendations");
      assertNotNull(
          "Reopening settings must retain the persisted switch",
          awaitVisibleText("Recommendations: " + (expected ? "On" : "Off"), true));
      assertNotNull(awaitVisibleText("Only content this TV provider permits", false));
    } catch (AssertionError failure) {
      throw new AssertionError(failure.getMessage() + "\n" + captureFailureWindows(), failure);
    } finally {
      if (originalAccessibilityFlags != null) {
        AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
        service.flags = originalAccessibilityFlags;
        instrumentation.getUiAutomation().setServiceInfo(service);
      }
      if (preferences != null) {
        SharedPreferences.Editor restore = preferences.edit();
        if (originallyPresent) restore.putBoolean("enabled", original);
        else restore.remove("enabled");
        assertTrue("Restore the original recommendation preference", restore.commit());
      }
      if (activity != null) {
        NativeLifecycleTestActivity closing = activity;
        instrumentation.runOnMainSync(closing::finish);
        instrumentation.waitForIdleSync();
      }
      NativeLifecycleTestActivity.fixture = null;
    }
  }

  private String captureFailureWindows() {
    AtomicReference<String> nativeWindows = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          StringBuilder description = new StringBuilder();
          for (View root : WindowInspector.getGlobalWindowViews()) {
            description
                .append("\nNative window ")
                .append(root.getClass().getName())
                .append(" attached=")
                .append(root.isAttachedToWindow())
                .append(" focused=")
                .append(root.hasWindowFocus())
                .append(" shownText=");
            collectShownText(root, description);
          }
          nativeWindows.set(description.toString());
        });
    AccessibilityNodeInfo active = instrumentation.getUiAutomation().getRootInActiveWindow();
    String accessibility = active == null ? "null" : visibleTexts(active).toString();
    String screenshotPath;
    try {
      File directory = instrumentation.getTargetContext().getExternalFilesDir(null);
      if (directory == null)
        throw new IllegalStateException("External artifact directory unavailable");
      File destination = new File(directory, "settings-test-failure.png");
      Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
      if (screenshot == null) throw new IllegalStateException("Screenshot unavailable");
      try (FileOutputStream output = new FileOutputStream(destination)) {
        if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
          throw new IllegalStateException("Screenshot compression failed");
        }
      } finally {
        screenshot.recycle();
      }
      screenshotPath = destination.getAbsolutePath();
    } catch (Exception unavailable) {
      screenshotPath = "unavailable: " + unavailable;
    }
    return "Active accessibility text="
        + accessibility
        + nativeWindows.get()
        + "\nFailure screenshot="
        + screenshotPath;
  }

  private static void collectShownText(View view, StringBuilder description) {
    if (!view.isShown()) return;
    if (view instanceof TextView) {
      description.append('[').append(((TextView) view).getText()).append(']');
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        collectShownText(group.getChildAt(index), description);
      }
    }
  }

  private AccessibilityNodeInfo awaitVisibleText(String text, boolean exact) {
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : found) {
          if (node.isVisibleToUser()
              && node.getText() != null
              && (!exact || text.contentEquals(node.getText()))) return node;
        }
      }
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("No visible native dialog text: " + text);
    return null;
  }

  private void awaitRecommendationDialogDismissed(
      NativeLifecycleTestActivity activity, String switchLabel) {
    AtomicBoolean dismissed = new AtomicBoolean();
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      instrumentation.runOnMainSync(
          () -> {
            boolean attachedDialog = false;
            for (View root : WindowInspector.getGlobalWindowViews()) {
              if (root.isAttachedToWindow() && containsShownText(root, switchLabel)) {
                attachedDialog = true;
              }
            }
            dismissed.set(!attachedDialog && activity.getWindow().getDecorView().hasWindowFocus());
          });
      if (dismissed.get()) return;
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("The recommendation dialog must disappear and return window focus before reopening");
  }

  private static boolean containsShownText(View view, String text) {
    if (!view.isShown()) return false;
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        if (containsShownText(group.getChildAt(index), text)) return true;
      }
    }
    return false;
  }

  private void clickScrollableDialogText(String dialogTitle, String text) {
    awaitVisibleText(dialogTitle, true);
    for (int scrolls = 0; scrolls <= 8; scrolls++) {
      AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
      assertNotNull("The native dialog must remain attached", root);
      AccessibilityNodeInfo target = visibleExactText(root, text);
      if (target != null) {
        clickText(text);
        return;
      }
      assertTrue("Dialog action must be reachable within eight scrolls: " + text, scrolls < 8);
      AccessibilityNodeInfo list = scrollableList(root);
      assertNotNull("Offscreen dialog action requires an accessible native list", list);
      List<String> before = visibleTexts(list);
      assertTrue(
          "Native dialog list must accept an accessibility scroll",
          list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
      instrumentation.waitForIdleSync();
      long deadline = SystemClock.uptimeMillis() + 1500;
      boolean progressed = false;
      do {
        AccessibilityNodeInfo updated = instrumentation.getUiAutomation().getRootInActiveWindow();
        AccessibilityNodeInfo current = updated == null ? null : scrollableList(updated);
        if (current != null && !before.equals(visibleTexts(current))) {
          progressed = true;
          break;
        }
        SystemClock.sleep(25);
      } while (SystemClock.uptimeMillis() < deadline);
      assertTrue("Accepted native list scroll must reveal different visible items", progressed);
    }
    fail("Unreachable native dialog action: " + text);
  }

  private static AccessibilityNodeInfo visibleExactText(AccessibilityNodeInfo root, String text) {
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
      if (node.isVisibleToUser() && node.getText() != null && text.contentEquals(node.getText())) {
        return node;
      }
    }
    return null;
  }

  private static AccessibilityNodeInfo scrollableList(AccessibilityNodeInfo node) {
    if (node.isVisibleToUser()
        && node.isScrollable()
        && "android.widget.ListView".contentEquals(node.getClassName())) return node;
    for (int index = 0; index < node.getChildCount(); index++) {
      AccessibilityNodeInfo child = node.getChild(index);
      if (child == null) continue;
      AccessibilityNodeInfo list = scrollableList(child);
      if (list != null) return list;
    }
    return null;
  }

  private static List<String> visibleTexts(AccessibilityNodeInfo node) {
    List<String> texts = new ArrayList<>();
    if (node.isVisibleToUser() && node.getText() != null) texts.add(node.getText().toString());
    for (int index = 0; index < node.getChildCount(); index++) {
      AccessibilityNodeInfo child = node.getChild(index);
      if (child != null) texts.addAll(visibleTexts(child));
    }
    return texts;
  }

  private void clickText(String text) {
    clickReadyTarget(text, false);
  }

  private void clickReadyTarget(String match, boolean viewId) {
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
      if (root != null && root.refresh()) {
        List<AccessibilityNodeInfo> candidates =
            viewId
                ? root.findAccessibilityNodeInfosByViewId(match)
                : root.findAccessibilityNodeInfosByText(match);
        for (AccessibilityNodeInfo candidate : candidates) {
          if (!candidate.refresh() || !candidate.isVisibleToUser() || !candidate.isEnabled())
            continue;
          if (!viewId
              && (candidate.getText() == null || !match.contentEquals(candidate.getText()))) {
            continue;
          }
          AccessibilityNodeInfo clickable = candidate;
          while (clickable != null && clickable.refresh()) {
            boolean action =
                clickable.isClickable()
                    || clickable
                        .getActionList()
                        .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            if (clickable.isVisibleToUser()
                && clickable.isEnabled()
                && action
                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
              instrumentation.waitForIdleSync();
              return;
            }
            clickable = clickable.getParent();
          }
        }
      }
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("No refreshed visible enabled native action accepted an accessibility click: " + match);
  }
}
