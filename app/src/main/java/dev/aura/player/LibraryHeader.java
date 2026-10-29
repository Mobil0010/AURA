package dev.aura.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** A clipped app bar that follows touch distance; list inertia never toggles the bar. */
final class LibraryHeader extends FrameLayout {
    private View expanded,thin;private float fraction,lastY,startX,travel;private boolean touching,horizontal;private int fullHeight;
    private Runnable settled=()->{};private ValueAnimator animation;private java.util.function.Consumer<Float> changed=p->{};
    LibraryHeader(Context c){super(c);setClipChildren(true);setClipToPadding(true);setBackgroundColor(Ui.BG);}
    void content(View expanded,View thin,float initial,java.util.function.Consumer<Float> changed){cancelMotion();removeAllViews();this.expanded=expanded;this.thin=thin;this.changed=changed;addView(expanded,new LayoutParams(-1,-2));thin.setBackgroundColor(Ui.BG);addView(thin,new LayoutParams(-1,Ui.dp(getContext(),48)));fraction=Math.max(0,Math.min(1,initial));paint();requestLayout();}
    void onSettled(Runnable action){settled=action;}
    void settled(){settled.run();}
    int visibleHeight(){return Math.round(fullHeight-(fullHeight-Ui.dp(getContext(),48))*fraction);}
    @Override public void draw(android.graphics.Canvas canvas){int save=canvas.save();canvas.clipRect(0,0,getWidth(),visibleHeight());super.draw(canvas);canvas.restoreToCount(save);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&event.getY()>visibleHeight())return false;return super.dispatchTouchEvent(event);}
    float progress(){return fraction;}
    void touch(MotionEvent event){switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN->{cancelMotion();touching=true;horizontal=false;startX=event.getRawX();lastY=event.getRawY();travel=0;}
        case MotionEvent.ACTION_MOVE->{if(!touching||horizontal)return;float dy=lastY-event.getRawY();lastY=event.getRawY();if(Math.abs(event.getRawX()-startX)>Ui.dp(getContext(),24)&&Math.abs(travel)<Ui.dp(getContext(),16)){horizontal=true;return;}float before=travel;travel+=dy;float dead=Ui.dp(getContext(),16);if(Math.abs(travel)<=dead)return;float used=Math.abs(before)<dead?Math.copySign(Math.abs(travel)-dead,travel):dy;setProgress(fraction+used*.82f/Math.max(Ui.dp(getContext(),100),fullHeight-Ui.dp(getContext(),48)));}
        case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->release();
    }}
    void release(){if(!touching)return;touching=false;if(fraction<.08f)animateTo(0);else if(fraction>.92f)animateTo(1);else settled();}
    void animateTo(float end){cancelMotion();if(!Ui.motion(getContext())||Math.abs(end-fraction)<.001f){setProgress(end);settled();return;}animation=ValueAnimator.ofFloat(fraction,end);animation.setDuration(240);animation.setInterpolator(new DecelerateInterpolator());animation.addUpdateListener(a->setProgress((float)a.getAnimatedValue()));animation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(animation==a){animation=null;settled();}}});animation.start();}
    void cancelMotion(){if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}touching=false;}
    private void setProgress(float value){float next=Math.max(0,Math.min(1,value));if(Math.abs(next-fraction)<.0001f)return;fraction=next;paint();invalidate();changed.accept(fraction);}
    private void paint(){if(expanded==null)return;float offset=Math.max(0,fullHeight-Ui.dp(getContext(),48))*fraction;expanded.setVisibility(fraction>=.95f?INVISIBLE:VISIBLE);expanded.setTranslationY(-offset);expanded.setAlpha(Math.max(0,1-Math.max(0,fraction-.65f)/.35f));expanded.setImportantForAccessibility(fraction>.9f?IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:IMPORTANT_FOR_ACCESSIBILITY_AUTO);thin.setAlpha(Math.max(0,(fraction-.72f)/.28f));thin.setVisibility(fraction>.72f?VISIBLE:INVISIBLE);thin.setImportantForAccessibility(fraction>.9f?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
    @Override protected void onMeasure(int width,int height){int w=MeasureSpec.getSize(width);if(expanded==null){setMeasuredDimension(w,0);return;}expanded.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));fullHeight=Math.max(Ui.dp(getContext(),48),expanded.getMeasuredHeight());thin.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Ui.dp(getContext(),48),MeasureSpec.EXACTLY));setMeasuredDimension(w,fullHeight);paint();}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){if(expanded!=null){expanded.layout(0,0,r-l,fullHeight);thin.layout(0,0,r-l,Ui.dp(getContext(),48));}}
    @Override protected void onDetachedFromWindow(){cancelMotion();super.onDetachedFromWindow();}
}
