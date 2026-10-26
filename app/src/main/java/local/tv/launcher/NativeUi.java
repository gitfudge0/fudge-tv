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
  private final java.util.WeakHashMap<View, android.animation.ValueAnimator> surfaces =
      new java.util.WeakHashMap<>();

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
    button.setOnClickListener(
        v -> {
          if (!reducedMotion()) {
            button.animate().cancel();
            button.animate().withEndAction(null).setStartDelay(0);
            button.setAlpha(1);
            button.setTranslationY(0);
            button.setScaleX(.96f);
            button.setScaleY(.96f);
            button.animate().scaleX(1).scaleY(1).setDuration(140).start();
          }
          click.run();
        });
    button.setOnFocusChangeListener(
        (v, focused) -> {
          animateSurface(
              button, focused ? Color.WHITE : 0x30ffffff, focused ? BACKGROUND : Color.WHITE, 9);
          focusMotion(button, focused, px(10));
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
    surface.setEnterFadeDuration(reducedMotion() ? 0 : 160);
    surface.setExitFadeDuration(reducedMotion() ? 0 : 160);
    button.setBackground(surface);
    button.setOnFocusChangeListener(
        (view, focused) -> {
          updateNavMotion(button);
          focusMotion(button, focused, px(7));
          button.setTextColor(focused ? BACKGROUND : Color.WHITE);
        });
    return button;
  }

  void updateNavMotion(Button button) {
    if (button.getBackground() instanceof StateListDrawable) {
      StateListDrawable background = (StateListDrawable) button.getBackground();
      background.setEnterFadeDuration(reducedMotion() ? 0 : 160);
      background.setExitFadeDuration(reducedMotion() ? 0 : 160);
      if (reducedMotion()) background.jumpToCurrentState();
    }
  }

  void inputFocus(android.widget.EditText input, boolean focused) {
    animateSurface(input, focused ? 0x30ffffff : 0x18ffffff, Color.WHITE, 12);
    ((GradientDrawable) input.getBackground())
        .setStroke(px(2), focused ? 0xfff0eee9 : Color.TRANSPARENT);
    focusMotion(input, focused, px(4));
  }

  private void animateSurface(TextView button, int color, int text, int radius) {
    android.animation.ValueAnimator previous = surfaces.remove(button);
    if (previous != null) previous.cancel();
    int from =
        button.getBackground() instanceof GradientDrawable
            ? ((GradientDrawable) button.getBackground()).getColor().getDefaultColor()
            : Color.TRANSPARENT;
    int oldText = button.getCurrentTextColor();
    GradientDrawable background = surface(from, radius);
    button.setBackground(background);
    if (reducedMotion()) {
      background.setColor(color);
      button.setTextColor(text);
      return;
    }
    android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0, 1);
    surfaces.put(button, animation);
    android.animation.ArgbEvaluator colors = new android.animation.ArgbEvaluator();
    animation.addUpdateListener(
        a -> {
          float progress = (float) a.getAnimatedValue();
          background.setColor((int) colors.evaluate(progress, from, color));
          button.setTextColor((int) colors.evaluate(progress, oldText, text));
        });
    animation.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator ended) {
            if (surfaces.get(button) == animation) surfaces.remove(button);
            animation.removeAllUpdateListeners();
            animation.removeAllListeners();
          }
        });
    animation.setDuration(160);
    animation.start();
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

  static void focusMotion(View view, boolean focused, float elevation) {
    view.animate().cancel();
    view.animate().withEndAction(null).setStartDelay(0);
    view.setAlpha(1);
    view.setTranslationY(0);
    if (reducedMotion()) view.setTranslationZ(focused ? elevation : 0);
    else
      view.animate()
          .translationZ(focused ? elevation : 0)
          .scaleX(1)
          .scaleY(1)
          .setDuration(160)
          .start();
  }

  static void reveal(View view, float offset) {
    view.animate().cancel();
    view.animate().withEndAction(null).setStartDelay(0);
    view.setAlpha(1);
    view.setTranslationY(0);
    view.setScaleX(1);
    view.setScaleY(1);
    if (reducedMotion() || !view.isAttachedToWindow()) return;
    view.setAlpha(0);
    view.setTranslationY(offset);
    view.animate()
        .alpha(1)
        .translationY(0)
        .setDuration(200)
        .setInterpolator(new DecelerateInterpolator())
        .start();
  }

  static boolean reducedMotion() {
    return !android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  static void move(View view, float x, float scale, boolean instant) {
    view.animate().cancel();
    view.animate().withEndAction(null).setStartDelay(0);
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
