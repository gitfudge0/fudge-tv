package local.tv.launcher;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;

/** Crossfades up to four existing bitmap references, without snapshots or per-frame allocation. */
final class BackdropView extends View {
    private static final class Layer {final Bitmap bitmap;float weight;Layer(Bitmap b,float w){bitmap=b;weight=w;}}
    private final ArrayList<Layer> layers=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF destination=new RectF();
    private ValueAnimator animator;
    private Bitmap requested;
    private int generation;
    BackdropView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setImageBitmap(Bitmap image){
        if(image==null || image.isRecycled() || image==requested)return;
        requested=image;int token=++generation;if(animator!=null)animator.cancel();
        if(layers.isEmpty() || !ValueAnimator.areAnimatorsEnabled()){layers.clear();layers.add(new Layer(image,1));invalidate();return;}
        while(layers.size()>3){int smallest=0;for(int i=1;i<layers.size();i++)if(layers.get(i).weight<layers.get(smallest).weight)smallest=i;layers.remove(smallest);}
        float total=0;for(Layer layer:layers)total+=layer.weight;
        if(total>0)for(Layer layer:layers)layer.weight/=total;
        Layer incoming=new Layer(image,0);layers.add(incoming);
        float[] starting=new float[layers.size()];for(int i=0;i<starting.length;i++)starting[i]=layers.get(i).weight;
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(260);animator.setInterpolator(new PathInterpolator(.2f,0,.2f,1));
        animator.addUpdateListener(a->{if(token!=generation)return;float f=(float)a.getAnimatedValue();for(int i=0;i<layers.size();i++)layers.get(i).weight=layers.get(i)==incoming?f:starting[i]*(1-f);invalidate();});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(token!=generation)return;layers.clear();incoming.weight=1;layers.add(incoming);invalidate();}});animator.start();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float accumulated=0; // Normalized source-over weights avoid a fullscreen offscreen layer.
        for(int i=0;i<layers.size();i++){
            Layer layer=layers.get(i);Bitmap b=layer.bitmap;if(layer.weight<=0 || b.isRecycled())continue;
            float scale=Math.max(getWidth()/(float)b.getWidth(),getHeight()/(float)b.getHeight());float w=b.getWidth()*scale,h=b.getHeight()*scale;
            destination.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
            accumulated+=layer.weight;paint.setAlpha(Math.round(255*layer.weight/accumulated));canvas.drawBitmap(b,null,destination,paint);
        }
        paint.setAlpha(255);
    }
    @Override protected void onDetachedFromWindow(){generation++;if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
}
