package local.tv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.view.ViewParent;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.view.animation.PathInterpolator;

/** One gliding focus indicator. Geometry follows scrolling/transforms without changing layout. */
final class FocusGlass extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawn=new RectF(),start=new RectF(),target=new RectF(),clip=new RectF();
    private final Matrix matrix=new Matrix();
    private View clipParent;
    private final int[] origin=new int[2];
    private final float unit;
    private View selected;
    private ValueAnimator animator;
    private float fraction=1;
    private boolean initialized;
    FocusGlass(Context context,float unit){super(context);this.unit=unit;setFocusable(false);setClickable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void focus(View view){
        Object image=view.getTag(R.id.focus_artwork);View actual=image instanceof View?(View)image:view;
        if(actual==selected && initialized)return;
        if(animator!=null)animator.cancel();
        View previousArea=clipParent;selected=actual;clipParent=null;ViewParent parent=selected.getParent();while(parent instanceof View){if(parent instanceof RecyclerView){clipParent=(View)parent;break;}parent=parent.getParent();}readTarget();
        if(!initialized || previousArea!=clipParent || !ValueAnimator.areAnimatorsEnabled()){drawn.set(target);start.set(target);fraction=1;initialized=true;invalidate();return;}
        start.set(drawn);fraction=0;
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(190);animator.setInterpolator(new PathInterpolator(.2f,0,.2f,1));
        animator.addUpdateListener(a->{fraction=(float)a.getAnimatedValue();invalidate();});animator.start();
    }
    private void readTarget(){
        if(selected==null)return;
        getLocationOnScreen(origin);bounds(selected,target);
    }
    private void bounds(View view,RectF result){
        matrix.reset();view.transformMatrixToGlobal(matrix);result.set(0,0,view.getWidth(),view.getHeight());matrix.mapRect(result);result.offset(-origin[0],-origin[1]);
    }
    void follow(){
        if(selected==null || !selected.isAttachedToWindow())return;
        float l=target.left,t=target.top,r=target.right,b=target.bottom;readTarget();
        if(l!=target.left || t!=target.top || r!=target.right || b!=target.bottom)invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(!initialized || selected==null || !selected.isAttachedToWindow())return;
        readTarget();drawn.set(start.left+(target.left-start.left)*fraction,start.top+(target.top-start.top)*fraction,start.right+(target.right-start.right)*fraction,start.bottom+(target.bottom-start.bottom)*fraction);
        int save=canvas.save();if(clipParent!=null){bounds(clipParent,clip);canvas.clipRect(clip);}
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x0affffff);canvas.drawRoundRect(drawn,10*unit,10*unit,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f*unit);paint.setColor(Color.WHITE);canvas.drawRoundRect(drawn,10*unit,10*unit,paint);canvas.restoreToCount(save);
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
}
