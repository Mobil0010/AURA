package dev.aura.player;
import android.content.*;import android.net.Uri;import org.json.*;import java.io.*;import java.util.*;
/** Explicit, private full-file offline copies. Partial files never become playable. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class OfflineMusic {
 static final Object LOCK=new Object();static File directory(Context c){File d=new File(c.getFilesDir(),"offline-music");d.mkdirs();return d;}
 static File file(Context c,String uri){return new File(directory(c),MusicCollections.key(uri)+".audio");}
 static JSONObject index(Context c){try{return new JSONObject(MusicCollections.prefs(c).getString("offline-index","{}"));}catch(Exception e){return new JSONObject();}}
 static void save(Context c,JSONObject j){MusicCollections.prefs(c).edit().putString("offline-index",j.toString()).commit();}
 static File available(Context c,String uri){synchronized(LOCK){File f=file(c,uri);JSONObject row=index(c).optJSONObject(MusicCollections.key(uri));return row!=null&&f.isFile()&&f.length()==row.optLong("size",-1)?f:null;}}
 static long quota(Context c){return MusicCollections.prefs(c).getLong("offline-quota",1024L*1024*1024);}
 static long used(Context c){long total=0;File[] files=directory(c).listFiles((d,n)->n.endsWith(".audio"));if(files!=null)for(File f:files)total+=f.length();return total;}
 static List<Track> tracks(Context c){List<Track> out=new ArrayList<>();synchronized(LOCK){JSONObject j=index(c);for(var it=j.keys();it.hasNext();)try{JSONObject row=j.getJSONObject(it.next());Track t=Track.from(row.getJSONObject("track"));if(available(c,t.uri)!=null)out.add(t);}catch(Exception ignored){}}return out;}
 static void remove(Context c,String uri){synchronized(LOCK){JSONObject j=index(c);j.remove(MusicCollections.key(uri));file(c,uri).delete();save(c,j);}}
 static void makeRoom(Context c,long size,String current)throws Exception{synchronized(LOCK){long max=quota(c);if(size<0||size>max)throw new IOException("파일이 오프라인 보관 한도보다 큽니다.");if(used(c)+size<=max)return;if(!MusicCollections.prefs(c).getBoolean("offline-clean",false))throw new IOException("오프라인 공간이 부족합니다. 용량을 늘리거나 보관곡을 정리해 주세요.");List<Track> old=tracks(c);old.sort(Comparator.comparingLong(t->file(c,t.uri).lastModified()));for(Track t:old){if(t.uri.equals(current)||t.uri.equals(PlaybackTools.activeUri))continue;remove(c,t.uri);if(used(c)+size<=max)return;}throw new IOException("오프라인 공간이 부족합니다.");}}
 static void download(Context c,Track t,java.util.function.LongConsumer progress)throws Exception{
  try(DriveDataSource source=new DriveDataSource(c)){download(c,t,source,progress);}
 }
 static void download(Context c,Track t,DriveDataSource source,java.util.function.LongConsumer progress)throws Exception{
  if(!t.drive())return;if(available(c,t.uri)!=null)return;File target=file(c,t.uri),temp=new File(target+".partial");temp.delete();
  try(OutputStream out=new FileOutputStream(temp)){long total=source.open(new androidx.media3.datasource.DataSpec.Builder().setUri(t.uri).build());makeRoom(c,total,t.uri);long done=0;byte[] b=new byte[65536];int n;while((n=source.read(b,0,b.length))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("보관을 취소했습니다.");done+=n;if(done>quota(c))throw new IOException("보관 용량 한도를 초과했습니다.");out.write(b,0,n);progress.accept(done);}out.flush();if(total>=0&&done!=total)throw new EOFException("다운로드가 완료되지 않았습니다.");
   synchronized(LOCK){if(!temp.renameTo(target))throw new IOException("보관 파일을 저장하지 못했습니다.");JSONObject j=index(c);j.put(MusicCollections.key(t.uri),new JSONObject().put("size",done).put("track",t.json()));save(c,j);}
  }finally{temp.delete();}
 }
 static final java.util.concurrent.ConcurrentHashMap<String,List<Track>> jobs=new java.util.concurrent.ConcurrentHashMap<>();
 static void enqueue(Context c,List<Track> tracks){if(tracks.isEmpty())return;List<Track> cloud=new ArrayList<>();for(Track t:tracks)if(t.drive())cloud.add(t);if(cloud.isEmpty())return;if(jobs.size()>=4){android.widget.Toast.makeText(c,"보관 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.",android.widget.Toast.LENGTH_SHORT).show();return;}String key=java.util.UUID.randomUUID().toString();jobs.put(key,List.copyOf(cloud));try{androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,OfflineService.class).putExtra("job",key));}catch(Exception e){jobs.remove(key);android.widget.Toast.makeText(c,"오프라인 보관을 시작하지 못했습니다.",android.widget.Toast.LENGTH_SHORT).show();}}
}
