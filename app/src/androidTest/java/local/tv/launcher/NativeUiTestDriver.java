package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import androidx.recyclerview.widget.RecyclerView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real remote navigation prepares the same provider row reached by a TV user. */
final class NativeUiTestDriver {
  private NativeUiTestDriver() {}

  static RecyclerView reachProviderRow(
      Instrumentation instrumentation, NativeUiTestActivity activity) {
    awaitFocus(instrumentation, activity, "watch:0");
    for (String expected : new String[] {"app:fixture.app0", "published:0"}) {
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
      instrumentation.waitForIdleSync();
      awaitFocus(instrumentation, activity, expected);
    }
    AtomicReference<RecyclerView> row = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          View focus = activity.home.findFocus();
          assertNotNull(focus);
          assertTrue(focus.getParent() instanceof RecyclerView);
          row.set((RecyclerView) focus.getParent());
          RecyclerView.ViewHolder holder = row.get().findContainingViewHolder(focus);
          assertTrue(holder instanceof NativeCardAdapter.Holder);
          assertEquals(0, holder.getBindingAdapterPosition());
          assertEquals("published:0", ((NativeCardAdapter.Holder) holder).card.id);
        });
    return row.get();
  }

  static void awaitFocus(
      Instrumentation instrumentation, NativeUiTestActivity activity, String expected) {
    long deadline = SystemClock.uptimeMillis() + 3000;
    AtomicBoolean ready = new AtomicBoolean();
    AtomicReference<String> observed = new AtomicReference<>("none");
    do {
      instrumentation.runOnMainSync(
          () -> {
            View focused = activity.home.findFocus();
            observed.set(identity(focused));
            ready.set(expected.equals(observed.get()) && focused.hasWindowFocus());
          });
      if (ready.get()) return;
      SystemClock.sleep(16);
    } while (SystemClock.uptimeMillis() < deadline);
    instrumentation.runOnMainSync(
        () -> {
          StringBuilder detail =
              new StringBuilder("expected=")
                  .append(expected)
                  .append(", observed=")
                  .append(observed.get());
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          detail
              .append(", scrollState=")
              .append(vertical.getScrollState())
              .append(", pendingUpdates=")
              .append(vertical.hasPendingAdapterUpdates());
          for (int i = 0; i < vertical.getChildCount(); i++) {
            View section = vertical.getChildAt(i);
            int position = vertical.getChildAdapterPosition(section);
            detail.append("\nrow:").append(position).append(' ').append(bounds(section));
            if (position < 0 || !(section instanceof android.view.ViewGroup)) continue;
            RecyclerView row = (RecyclerView) ((android.view.ViewGroup) section).getChildAt(2);
            detail
                .append(" nestedFocus=")
                .append(row.hasFocus())
                .append(" nestedBounds=")
                .append(bounds(row));
            for (int j = 0; j < row.getChildCount(); j++) {
              View card = row.getChildAt(j);
              RecyclerView.ViewHolder holder = row.getChildViewHolder(card);
              detail
                  .append("\n card:")
                  .append(holder.getBindingAdapterPosition())
                  .append(" enabled=")
                  .append(card.isEnabled())
                  .append(" focusable=")
                  .append(card.isFocusable())
                  .append(" focused=")
                  .append(card.isFocused())
                  .append(' ')
                  .append(bounds(card));
            }
          }
          View focus = activity.home.findFocus();
          if (focus != null)
            detail
                .append("\nnextDownAfterDeadline=")
                .append(identity(focus.focusSearch(View.FOCUS_DOWN)));
          android.os.Bundle bundle = new android.os.Bundle();
          bundle.putString("nativeFocusDiagnostic", detail.toString());
          instrumentation.sendStatus(0, bundle);
        });
    fail("Real D-pad focus expected " + expected + " within 3 seconds; observed " + observed.get());
  }

  private static String bounds(View view) {
    int[] screen = new int[2];
    view.getLocationOnScreen(screen);
    android.graphics.Rect visible = new android.graphics.Rect();
    boolean onScreen = view.getGlobalVisibleRect(visible);
    return "["
        + screen[0]
        + ","
        + screen[1]
        + ","
        + view.getWidth()
        + ","
        + view.getHeight()
        + "] visible="
        + onScreen
        + ":"
        + visible;
  }

  static String identity(View focused) {
    if (focused == null) return "none";
    if (focused.getId() == R.id.nav_home) return "home";
    if (focused.getId() == R.id.search_input) return "searchInput";
    for (ViewParent parent = focused.getParent(); parent != null; parent = parent.getParent()) {
      if (parent instanceof RecyclerView) {
        RecyclerView.ViewHolder holder = ((RecyclerView) parent).findContainingViewHolder(focused);
        if (holder instanceof NativeCardAdapter.Holder) {
          NativeCard card = ((NativeCardAdapter.Holder) holder).card;
          return card == null ? "unbound card" : card.id;
        }
      }
    }
    return focused.getClass().getName() + ":" + focused.getId();
  }
}
