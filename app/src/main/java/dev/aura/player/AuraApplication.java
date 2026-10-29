package dev.aura.player;

import android.app.Application;
import java.io.*;

/** Keeps bounded, private diagnostics without media URLs, account details or exception messages. */
public final class AuraApplication extends Application {
    private static volatile String stage="앱 시작";
    static void stage(String value){stage=value;}
    @Override public void onCreate(){super.onCreate();Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{try{StringBuilder report=new StringBuilder("AURA "+BuildConfig.VERSION_NAME+" / Android "+android.os.Build.VERSION.SDK_INT+" / "+android.os.Build.MODEL+" / heap "+Runtime.getRuntime().maxMemory()+"\n"+stage+"\n");Throwable cause=error;for(int depth=0;cause!=null&&depth<4;depth++,cause=cause.getCause()){report.append(cause.getClass().getName()).append('\n');StackTraceElement[] stack=cause.getStackTrace();for(int i=0;i<Math.min(24,stack.length);i++)report.append("  ").append(stack[i]).append('\n');}try(FileOutputStream out=openFileOutput("last-crash.txt",MODE_PRIVATE)){out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Throwable ignored){}finally{if(previous!=null)previous.uncaughtException(thread,error);else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}}});}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW)ArtworkView.clearCache();}
    static String report(android.content.Context c){try{return new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"last-crash.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);}catch(IOException e){return "저장된 앱 종료 기록이 없습니다. 시스템의 강제 종료나 네이티브 오류는 기록되지 않을 수 있습니다.";}}
}
