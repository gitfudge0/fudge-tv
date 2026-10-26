package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies interruptible page transitions against actual attached views. */
@RunWith(AndroidJUnit4.class)
public class NativeMotionTest {
  @Test
  public void rapidReversalAndPauseSettleOnlyOnRequestedPage() {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeUiTestActivity activity =
        (NativeUiTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrumentation.runOnMainSync(
          () -> {
            activity.home.showSearch();
            ViewGroup outgoing = activity.home.findViewById(R.id.home_rows);
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, outgoing.getDescendantFocusability());
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                outgoing.getImportantForAccessibility());
            activity.home.showHome();
            activity.home.showSearch();
            activity.home.setActive(false);
          });
      android.os.SystemClock.sleep(300);
      instrumentation.runOnMainSync(
          () -> {
            ViewGroup rows = activity.home.findViewById(R.id.home_rows);
            assertEquals(View.GONE, rows.getVisibility());
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, rows.getDescendantFocusability());
            View input = activity.home.findViewById(R.id.search_input);
            assertTrue(input.isShown());
            assertTrue(input.hasFocus());
            View search = activity.home.findViewById(R.id.search_rows);
            assertEquals(1f, search.getAlpha(), 0f);
            activity.home.setActive(true);
            activity.home.showHome();
            activity.home.setActive(false);
            assertEquals(View.VISIBLE, rows.getVisibility());
            assertEquals(1f, rows.getAlpha(), 0f);
            assertEquals(0f, rows.getTranslationY(), 0f);
          });
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  private static Object field(Object target, String name) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static View action(View view, String title) {
    if (view instanceof android.widget.Button
        && title.contentEquals(((android.widget.Button) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View result = action(((ViewGroup) view).getChildAt(i), title);
        if (result != null) return result;
      }
    return null;
  }

  @Test
  public void dialogShellPersistsAndInterruptedExitCannotSurviveRelease() {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeUiTestActivity activity =
        (NativeUiTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrumentation.runOnMainSync(
          () -> {
            activity.home.showSettings();
            android.app.Dialog settings = (android.app.Dialog) field(activity.home, "dialog");
            action((View) field(activity.home, "dialogPanel"), "Your apps").performClick();
            assertSame(settings, field(activity.home, "dialog"));
            assertTrue(settings.isShowing());
            activity.home.handleBack();
            assertNull(field(activity.home, "dialog"));
            activity.home.showSettings();
            assertFalse(settings.isShowing());
            android.app.Dialog replacement = (android.app.Dialog) field(activity.home, "dialog");
            activity.home.setRefreshBusy(true);
            assertNotNull(action((View) field(activity.home, "dialogPanel"), "Refreshing…"));
            activity.home.setRefreshBusy(false);
            assertNotNull(action((View) field(activity.home, "dialogPanel"), "Refresh"));
            activity.home.handleBack();
            activity.home.release();
            assertFalse(replacement.isShowing());
            assertNull(field(activity.home, "exitingDialog"));
          });
      android.os.SystemClock.sleep(300);
      instrumentation.runOnMainSync(() -> assertNull(field(activity.home, "dialog")));
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  @Test
  public void disabledAnimationsSettlePagesAndDialogsImmediately() {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeUiTestActivity activity =
        (NativeUiTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      if ("true".equals(InstrumentationRegistry.getArguments().getString("requireReducedMotion"))) {
        long deadline = android.os.SystemClock.uptimeMillis() + 3000;
        while (!NativeUi.reducedMotion() && android.os.SystemClock.uptimeMillis() < deadline)
          android.os.SystemClock.sleep(25);
        assertTrue(
            "Required reduced-motion run must observe disabled system animators",
            NativeUi.reducedMotion());
      } else {
        org.junit.Assume.assumeTrue(
            "Run separately with emulator animator duration scale zero", NativeUi.reducedMotion());
      }
      instrumentation.runOnMainSync(
          () -> {
            activity.home.setActive(true);
            activity.home.showSearch();
            View homeRows = activity.home.findViewById(R.id.home_rows);
            View searchPage = (View) field(activity.home, "searchPage");
            assertEquals(View.GONE, homeRows.getVisibility());
            assertEquals(View.VISIBLE, searchPage.getVisibility());
            assertEquals(1f, searchPage.getAlpha(), 0f);
            assertEquals(0f, searchPage.getTranslationY(), 0f);
            assertTrue(activity.home.findViewById(R.id.search_input).hasFocus());
            activity.home.showSettings();
            android.app.Dialog modal = (android.app.Dialog) field(activity.home, "dialog");
            View panel = (View) field(activity.home, "dialogPanel");
            assertTrue(modal.isShowing());
            assertEquals(.7f, modal.getWindow().getAttributes().dimAmount, .001f);
            assertEquals(1f, panel.getAlpha(), 0f);
            assertEquals(0f, panel.getTranslationY(), 0f);
            assertEquals(0f, panel.getTranslationX(), 0f);
            assertTrue(activity.home.findViewById(R.id.nav_settings).isSelected());
            activity.home.handleBack();
            assertFalse(modal.isShowing());
            assertNull(field(activity.home, "dialog"));
            assertNull(field(activity.home, "exitingDialog"));
            View searchNav = activity.home.findViewById(R.id.nav_search);
            assertTrue(searchNav.isSelected());
            assertFalse(activity.home.findViewById(R.id.nav_settings).isSelected());
            assertTrue(activity.home.findViewById(R.id.search_input).hasFocus());
            searchNav.requestFocus();
            android.graphics.Bitmap image =
                android.graphics.Bitmap.createBitmap(
                    44, 44, android.graphics.Bitmap.Config.ARGB_8888);
            searchNav.getBackground().setBounds(0, 0, 44, 44);
            searchNav.getBackground().draw(new android.graphics.Canvas(image));
            assertEquals(android.graphics.Color.WHITE, image.getPixel(22, 22));
            image.recycle();
          });
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }
}
