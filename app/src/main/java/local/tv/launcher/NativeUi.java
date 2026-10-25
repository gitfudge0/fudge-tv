package local.tv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;

/** Shared design coordinates preserve the existing 1920-pixel TV composition. */
final class NativeUi {
  static final int BACKGROUND = Color.rgb(9, 10, 12);
  static final int MUTED = Color.rgb(183, 184, 189);
  final Context context;
  final float scale;
  final Typeface font;

  NativeUi(Context context) {
    this.context = context;
    scale = context.getResources().getDisplayMetrics().widthPixels / 1920f;
    font = context.getResources().getFont(R.font.inter);
  }

  int px(float value) {
    return Math.max(1, Math.round(value * scale));
  }

  TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextColor(color);
    view.setTypeface(font);
    view.setTextSize(
        TypedValue.COMPLEX_UNIT_PX,
        size * scale * context.getResources().getConfiguration().fontScale);
    view.setIncludeFontPadding(false);
    return view;
  }

  Button button(String label, Runnable click) {
    Button button = new Button(context);
    button.setFocusableInTouchMode(true);
    button.setText(label);
    button.setAllCaps(false);
    button.setTypeface(font);
    button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * scale);
    button.setTextColor(Color.WHITE);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(px(23), px(14), px(23), px(14));
    button.setBackground(surface(0x30ffffff, 9));
    button.setOnClickListener(v -> click.run());
    button.setOnFocusChangeListener(
        (v, focused) -> {
          button.setTextColor(focused ? BACKGROUND : Color.WHITE);
          button.setBackground(surface(focused ? Color.WHITE : 0x30ffffff, 9));
          button.setElevation(focused ? px(10) : 0);
        });
    return button;
  }

  Button navButton(int icon, String label, Runnable click) {
    Button button = button("", click);
    button.setContentDescription(label);
    // Scope the original compact glyph size to navigation; input-card icons stay large.
    button.setForeground(
        new android.graphics.drawable.InsetDrawable(new NativeNavIcon(icon), px(12)));
    StateListDrawable surface = new StateListDrawable();
    surface.addState(new int[] {android.R.attr.state_focused}, surface(Color.WHITE, 22));
    surface.addState(new int[] {android.R.attr.state_selected}, surface(0x25ffffff, 22));
    surface.addState(new int[0], surface(Color.TRANSPARENT, 22));
    button.setBackground(surface);
    button.setOnFocusChangeListener(
        (view, focused) -> {
          button.setElevation(focused ? px(7) : 0);
          button.setTextColor(focused ? BACKGROUND : Color.WHITE);
        });
    return button;
  }

  GradientDrawable surface(int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(px(radius));
    return drawable;
  }

  GradientDrawable focusOutline() {
    GradientDrawable drawable = surface(Color.TRANSPARENT, 12);
    drawable.setStroke(px(4), 0xfff0eee9);
    return drawable;
  }

  static boolean reducedMotion() {
    return !android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  static void move(View view, float x, float scale, boolean instant) {
    view.animate().cancel();
    if (instant || reducedMotion()) {
      view.setTranslationX(x);
      view.setScaleX(scale);
      view.setScaleY(scale);
    } else {
      view.animate()
          .translationX(x)
          .scaleX(scale)
          .scaleY(scale)
          .setDuration(140)
          .setInterpolator(new DecelerateInterpolator(3))
          .start();
    }
  }
}
