package dev.aura.player;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Owns scans independently of Activity lifetime. All status is immutable and observable. */
public final class LibraryScanService extends Service {
    public record State(boolean running,String message,int completed,int total,long revision) {}
    public static volatile State state=new State(false,"",0,-1,0);
    private static final String CHANNEL="library_scan";private static final int ID=204;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());private Future<?> task;private long lastNotice;private boolean finished;
    public static void start(Context c){start(c,"local");}
    public static void start(Context c,String kind){start(c,kind,false);}
    public static void start(Context c,String kind,boolean additionsOnly){androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,LibraryScanService.class).setAction(kind).putExtra("additionsOnly",additionsOnly));}
    static void startFolder(Context c,FolderScope scope)throws Exception{androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,LibraryScanService.class).setAction(scope.drive()?"drive":"local").putExtra("scope",scope.json()));}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"라이브러리 검색",NotificationManager.IMPORTANCE_LOW));}
    private Notification notice(State s){PendingIntent open=PendingIntent.getActivity(this,204,new Intent(this,MainActivity.class).putExtra("libraryProgress",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent cancel=PendingIntent.getService(this,205,new Intent(this,LibraryScanService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_aura).setContentTitle("음악 라이브러리 검색 중").setContentText(s.message()).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).addAction(new Notification.Action.Builder(null,"취소",cancel).build());b.setProgress(Math.max(1,s.total()),s.completed(),s.total()<0);return b.build();}
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"cancel".equals(intent.getAction())){cancel("검색을 취소했습니다. 기존 라이브러리는 유지됩니다.");return START_NOT_STICKY;}if(task!=null)return START_NOT_STICKY;
        state=new State(true,"폴더를 확인하고 있습니다…",0,-1,state.revision());startForeground(ID,notice(state));
        boolean additionsOnly=intent!=null&&intent.getBooleanExtra("additionsOnly",false);String kind=intent==null?"local":intent.getAction();task=worker.submit(()->{boolean changed=false;java.util.List<String> failures=new java.util.ArrayList<>();try{Library library=new Library(this);if(!"drive".equals(kind)&&(!Library.roots(this).isEmpty()||!"all".equals(kind))){try{long before=Library.revision;if(intent!=null&&intent.hasExtra("scope"))library.scanFolder(FolderScope.from(intent.getStringExtra("scope")),this::report);else library.scan(Library.roots(this),this::report,additionsOnly);changed|=Library.revision!=before;}catch(Exception e){if(e instanceof InterruptedException||e instanceof java.io.InterruptedIOException)throw e;failures.add("기기 폴더를 읽지 못했습니다.");}}
            if("drive".equals(kind)||"all".equals(kind)&&!DriveLibrary.roots(this).isEmpty()){try{long before=DriveLibrary.revision;if(intent!=null&&intent.hasExtra("scope"))new DriveLibrary(this).scanFolder(FolderScope.from(intent.getStringExtra("scope")),this::report);else new DriveLibrary(this).scan(this::report,additionsOnly);changed|=DriveLibrary.revision!=before;}catch(Exception e){if(e instanceof InterruptedException||e instanceof java.io.InterruptedIOException)throw e;failures.add(e.getMessage()==null?"Google Drive를 다시 연결해 주세요.":e.getMessage());}}
            int count=library.load().size();boolean committed=changed;String message=failures.isEmpty()?count+"곡을 검색했습니다.":String.join(" ",failures)+" 읽지 못한 저장소의 기존 목록은 유지됩니다.";main.post(()->finishScan(message,committed));
        }catch(Exception e){boolean committed=changed;main.post(()->finishScan("검색을 중단했습니다. 완료되지 않은 저장소의 기존 목록은 유지됩니다.",committed));}});return START_NOT_STICKY;
    }

    private synchronized void report(Library.Progress p){if(finished)return;String message=p.phase()+" · "+p.completed()+(p.total()<0?"곡 발견":" / "+p.total()+"곡");state=new State(true,message,p.completed(),p.total(),state.revision());long now=SystemClock.elapsedRealtime();if(now-lastNotice>350){lastNotice=now;getSystemService(NotificationManager.class).notify(ID,notice(state));}}
    private synchronized void finishScan(String message,boolean changed){if(finished)return;finished=true;state=new State(false,message,state.completed(),state.total(),state.revision()+(changed?1:0));stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private void cancel(String message){if(task!=null)task.cancel(true);finishScan(message,false);}
    @Override public void onTimeout(int startId,int fgsType){cancel("검색 시간이 초과되었습니다. 앱에서 다시 검색해 주세요.");}
    @Override public void onDestroy(){worker.shutdownNow();if(!finished&&state.running())state=new State(false,"검색이 중단되었습니다. 다시 검색해 주세요.",state.completed(),state.total(),state.revision());finished=true;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
