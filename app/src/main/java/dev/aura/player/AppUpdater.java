package dev.aura.player;

import android.app.DownloadManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Application-scoped update state. Downloads survive Activity and process recreation. */
final class AppUpdater {
    static final String SOURCE="https://github.com/Mobil0010/AURA/releases/latest/download/update-v2.json";
    static final long MAX_APK=512L*1024*1024;
    enum Stage { IDLE,CHECKING,CURRENT,AVAILABLE,DOWNLOADING,VERIFYING,READY,ERROR }
    record Release(long code,String name,String url,String sha,long size,int minSdk,String notes) {
        static Release parse(String json)throws Exception{JSONObject j=new JSONObject(json);long code=j.getLong("versionCode"),size=j.getLong("size");String name=j.getString("versionName"),url=https(j.getString("apkUrl")),sha=j.getString("sha256").toLowerCase(Locale.ROOT);int min=j.optInt("minSdk",26);String notes=j.optString("notes","");if(code<1||size<1||size>MAX_APK||!sha.matches("[0-9a-f]{64}")||name.isBlank()||name.length()>80||notes.length()>20000||min<1)throw new IOException("업데이트 정보가 올바르지 않습니다.");return new Release(code,name,url,sha,size,min,notes);}
        String json()throws Exception{return new JSONObject().put("versionCode",code).put("versionName",name).put("apkUrl",url).put("sha256",sha).put("size",size).put("minSdk",minSdk).put("notes",notes).toString();}
    }
    record State(Stage stage,Release release,long bytes,String message) {}
    private static AppUpdater instance;
    static synchronized AppUpdater get(Context c){if(instance==null)instance=new AppUpdater(c.getApplicationContext());return instance;}
    private final Context context;private final SharedPreferences prefs;private final DownloadManager manager;private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile State state=new State(Stage.IDLE,null,0,"");private volatile boolean busy;private Release release;
    private AppUpdater(Context c){context=c;prefs=c.getSharedPreferences("updates",Context.MODE_PRIVATE);manager=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);try{String saved=prefs.getString("release","");if(!saved.isEmpty()){release=Release.parse(saved);if(release.code()>BuildConfig.VERSION_CODE)state=new State(Stage.AVAILABLE,release,0,"");else clearDownload();}}catch(Exception ignored){clearDownload();}}
    State state(){return state;}
    boolean automatic(){return prefs.getBoolean("automatic",true);}void automatic(boolean on){prefs.edit().putBoolean("automatic",on).apply();}
    static String https(String value)throws IOException{try{URI uri=new URI(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IOException("HTTPS 주소만 사용할 수 있습니다.");return uri.toASCIIString();}catch(URISyntaxException e){throw new IOException("올바르지 않은 업데이트 주소입니다.",e);}}
    synchronized void check(boolean manual){if(busy)return;if(prefs.getLong("download",-1)>=0){refresh();return;}if(!manual&&(!automatic()||System.currentTimeMillis()-prefs.getLong("checked",0)<6*60*60*1000L))return;busy=true;state=new State(Stage.CHECKING,release,0,"");executor.execute(()->{try{Release found=Release.parse(fetch(SOURCE));if(found.minSdk()>Build.VERSION.SDK_INT)throw new IOException("새 버전은 Android API "+found.minSdk()+" 이상이 필요합니다.");release=found;prefs.edit().putLong("checked",System.currentTimeMillis()).putString("release",found.json()).apply();state=new State(found.code()>BuildConfig.VERSION_CODE?Stage.AVAILABLE:Stage.CURRENT,found,0,"");}catch(FileNotFoundException e){prefs.edit().putLong("checked",System.currentTimeMillis()).apply();state=new State(Stage.IDLE,null,0,"아직 공개된 업데이트가 없습니다.");}catch(Exception e){state=new State(Stage.ERROR,release,0,"업데이트 확인 실패 · "+message(e));}finally{busy=false;}});}
    static String fetch(String address)throws Exception{String at=https(address);for(int i=0;i<6;i++){HttpURLConnection c=(HttpURLConnection)new URL(at).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","AURA/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Cache-Control","no-cache");try{int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");if(location==null)throw new IOException("업데이트 서버의 이동 주소가 없습니다.");at=https(new URI(at).resolve(location).toString());continue;}if(code==404)throw new FileNotFoundException();if(code!=200)throw new IOException("서버 응답 "+code);try(InputStream in=c.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>131072)throw new IOException("업데이트 정보 크기가 너무 큽니다.");bytes.write(buffer,0,n);}return bytes.toString(StandardCharsets.UTF_8.name());}}finally{c.disconnect();}}throw new IOException("업데이트 서버의 이동 횟수가 너무 많습니다.");}
    synchronized void download(){if(busy||release==null||release.code()<=BuildConfig.VERSION_CODE)return;if(prefs.getLong("download",-1)>=0){refresh();return;}try{File file=downloadFile();file.getParentFile().mkdirs();if(new StatFs(file.getParent()).getAvailableBytes()<release.size()+10*1024*1024)throw new IOException("업데이트를 저장할 공간이 부족합니다.");if(file.exists()&&!file.delete())throw new IOException("이전 다운로드 파일을 정리할 수 없습니다.");DownloadManager.Request request=new DownloadManager.Request(Uri.parse(release.url())).setTitle("AURA "+release.name()).setDescription("앱 업데이트 다운로드").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setDestinationUri(Uri.fromFile(file)).setAllowedOverMetered(true).setAllowedOverRoaming(false);long id=manager.enqueue(request);prefs.edit().putLong("download",id).putString("release",release.json()).apply();state=new State(Stage.DOWNLOADING,release,0,"");}catch(Exception e){state=new State(Stage.ERROR,release,0,"다운로드를 시작할 수 없습니다. "+message(e));}}
    synchronized void cancel(){if(busy)return;long id=prefs.getLong("download",-1);if(id>=0)manager.remove(id);clearDownload();state=new State(release==null?Stage.IDLE:Stage.AVAILABLE,release,0,"");}
    private void clearDownload(){prefs.edit().remove("download").apply();}
    synchronized void refresh(){if(busy||release==null||prefs.getLong("download",-1)<0||state.stage()==Stage.READY)return;busy=true;executor.execute(()->{try{
        File ready=file();if(ready.isFile()){state=new State(Stage.VERIFYING,release,ready.length(),"");verify(context,ready,release);state=new State(Stage.READY,release,release.size(),"");return;}
        try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(prefs.getLong("download",-1)))){
            if(cursor==null||!cursor.moveToFirst())throw new IOException("다운로드 기록을 찾을 수 없습니다. 다시 시도해 주세요.");int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(status==DownloadManager.STATUS_FAILED)throw new IOException("다운로드 실패 ("+cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))+")");
            if(status!=DownloadManager.STATUS_SUCCESSFUL){state=new State(Stage.DOWNLOADING,release,bytes,status==DownloadManager.STATUS_PAUSED?"네트워크 연결을 기다리고 있습니다.":"");return;}
            state=new State(Stage.VERIFYING,release,bytes,"");ready.getParentFile().mkdirs();File temp=new File(ready.getParentFile(),"download.partial");try{try(InputStream in=new FileInputStream(downloadFile());OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>release.size())throw new IOException("APK 크기 제한을 초과했습니다.");out.write(buffer,0,n);}}verify(context,temp,release);if(!temp.renameTo(ready))throw new IOException("검증한 APK를 저장할 수 없습니다.");}finally{temp.delete();}downloadFile().delete();state=new State(Stage.READY,release,release.size(),"");
        }
    }catch(Exception e){long id=prefs.getLong("download",-1);if(id>=0)manager.remove(id);if(release!=null)file().delete();clearDownload();state=new State(Stage.ERROR,release,0,"업데이트 파일을 사용할 수 없습니다. "+message(e));}finally{busy=false;}});}
    private String filename(){return "aura-"+release.code()+"-"+release.sha().substring(0,12)+".apk";}
    File file(){return new File(context.getFilesDir(),"updates/"+filename());}
    private File downloadFile(){File dir=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(dir==null)throw new IllegalStateException("다운로드 저장소를 사용할 수 없습니다.");return new File(dir,"updates/"+filename());}
    static void verify(Context context,File file,Release release)throws Exception{if(file.length()!=release.size())throw new IOException("APK 파일 크기가 일치하지 않습니다.");MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}if(!MessageDigest.isEqual(Hex.encode(digest.digest()).getBytes(StandardCharsets.US_ASCII),release.sha().getBytes(StandardCharsets.US_ASCII)))throw new IOException("APK 파일 검증에 실패했습니다.");PackageManager pm=context.getPackageManager();int flags=signingFlags(Build.VERSION.SDK_INT);PackageInfo archive=pm.getPackageArchiveInfo(file.toString(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);if(archive==null||!context.getPackageName().equals(archive.packageName))throw new IOException("AURA 업데이트 파일이 아닙니다.");long code=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;if(code!=release.code()||code<=BuildConfig.VERSION_CODE||!release.name().equals(archive.versionName)||archive.applicationInfo==null||archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("APK 버전 또는 Android 호환성이 일치하지 않습니다.");if(!compatibleSigners(archive,installed))throw new IOException("현재 앱과 업데이트 파일의 서명이 다릅니다.");}
    // The PackageManager validates the APK proof-of-rotation while collecting certificates.
    // Only a descendant of the installed CURRENT signer is accepted; shared ancestors
    // or a reversed lineage must not authorize an unrelated or retired signer.
    static boolean compatibleSigners(PackageInfo candidate,PackageInfo installed)throws Exception{
        Set<String> current=certificates(installed),next=certificates(candidate);
        if(current.equals(next))return true;
        if(Build.VERSION.SDK_INT<33||current.size()!=1||next.size()!=1||candidate.signingInfo==null||candidate.signingInfo.hasMultipleSigners())return false;
        Signature[] history=candidate.signingInfo.getSigningCertificateHistory();
        if(history==null||history.length<2)return false;
        for(Signature cert:history)if(current.contains(Hex.encode(MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()))))return true;
        return false;
    }
    // Android 9 collects archive certificates only when GET_SIGNATURES is set.
    // Request both fields on 28+; keep current signers authoritative when available.
    static int signingFlags(int sdk){return PackageManager.GET_SIGNATURES|(sdk>=28?PackageManager.GET_SIGNING_CERTIFICATES:0);}
    static Set<String> certificates(PackageInfo p)throws Exception{Signature[] signatures=Build.VERSION.SDK_INT>=28&&p.signingInfo!=null?p.signingInfo.getApkContentsSigners():null;if(signatures==null||signatures.length==0)signatures=p.signatures;if(signatures==null||signatures.length==0)throw new IOException("APK 서명을 확인할 수 없습니다.");Set<String> result=new HashSet<>();for(Signature s:signatures)result.add(Hex.encode(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return result;}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
