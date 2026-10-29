package dev.aura.player;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/** Resource limits only: never changes the user's signal format or enabled effects. */
final class PlaybackBudget {
    record Limits(boolean constrained,int audioBytes,int artworkBytes,int scanThreads,long preloadUs) {}
    private static volatile Limits cached;
    static Limits get(Context c){Limits value=cached;if(value!=null)return value;ActivityManager manager=c.getSystemService(ActivityManager.class);ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);value=choose(Build.VERSION.SDK_INT,manager.getMemoryClass(),manager.isLowRamDevice(),memory.totalMem);cached=value;return value;}
    static Limits choose(int sdk,int heapMb,boolean lowRam,long ramBytes){boolean small=lowRam||sdk<=28||heapMb<=128||ramBytes>0&&ramBytes<=3L*1024*1024*1024;return new Limits(small,(small?3:8)*1024*1024,(small?1:4)*1024*1024,small?1:2,small?2_000_000L:5_000_000L);}
}
