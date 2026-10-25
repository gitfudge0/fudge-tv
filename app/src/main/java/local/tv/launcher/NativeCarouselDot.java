package local.tv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Invalidating this small native surface updates elapsed fill without changing layout. */
final class NativeCarouselDot extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private float progress;

  public NativeCarouselDot(Context context) {
    this(context, false);
  }

  NativeCarouselDot(Context context, boolean selected) {
    super(context);
    setSelected(selected);
    setFocusableInTouchMode(true);
    setClickable(true);
    setOnFocusChangeListener(
        (v, focused) -> {
          NativeUi.move(this, 0, focused ? 1.15f : 1, false);
          invalidate();
        });
  }

  void setProgress(float progress) {
    this.progress = Math.max(0, Math.min(1, progress));
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float radius = getHeight() / 2f;
    paint.setColor(hasFocus() ? Color.WHITE : isSelected() ? 0x66ffffff : 0x75ffffff);
    canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
    if (isSelected() && progress > 0) {
      canvas.save();
      canvas.clipRect(0, 0, getWidth() * progress, getHeight());
      paint.setColor(Color.WHITE);
      canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
      canvas.restore();
    }
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return android.widget.Button.class.getName();
  }
}
