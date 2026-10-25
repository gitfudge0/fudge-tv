package local.tv.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Local vector line icons match the original Home, Search and Settings controls. */
final class NativeNavIcon extends Drawable {
  private final int kind;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  NativeNavIcon(int kind) {
    this.kind = kind;
    paint.setColor(Color.WHITE);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.65f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  @Override
  public boolean isStateful() {
    return true;
  }

  @Override
  protected boolean onStateChange(int[] state) {
    boolean focused = false;
    for (int value : state) if (value == android.R.attr.state_focused) focused = true;
    int color = focused ? NativeUi.BACKGROUND : Color.WHITE;
    if (paint.getColor() == color) return false;
    paint.setColor(color);
    invalidateSelf();
    return true;
  }

  @Override
  public void draw(Canvas canvas) {
    canvas.save();
    float size = Math.min(getBounds().width(), getBounds().height()) * .43f;
    canvas.translate(getBounds().centerX() - size / 2, getBounds().centerY() - size / 2);
    canvas.scale(size / 24, size / 24);
    Path p = new Path();
    if (kind == 0) {
      p.moveTo(3, 10);
      p.lineTo(12, 3);
      p.lineTo(21, 10);
      p.lineTo(21, 21);
      p.lineTo(16, 21);
      p.lineTo(16, 14);
      p.lineTo(8, 14);
      p.lineTo(8, 21);
      p.lineTo(3, 21);
      p.close();
      canvas.drawPath(p, paint);
    } else if (kind == 1) {
      canvas.drawCircle(10.5f, 10.5f, 6.5f, paint);
      canvas.drawLine(16, 16, 21, 21, paint);
    } else {
      for (int i = 0; i < 24; i++) {
        double angle = Math.PI * i / 12 - Math.PI / 2;
        float radius = i % 4 == 0 || i % 4 == 3 ? 10 : 8;
        float x = 12 + (float) Math.cos(angle) * radius, y = 12 + (float) Math.sin(angle) * radius;
        if (i == 0) p.moveTo(x, y);
        else p.lineTo(x, y);
      }
      p.close();
      canvas.drawPath(p, paint);
      canvas.drawCircle(12, 12, 3, paint);
    }
    canvas.restore();
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
