package dev.aura.player;
import android.content.Context;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class LyricsRepository {
    public record Result(String text,List<Lrc.Line> lines,String source) {}
    private final Context context;private final LrcClient client;
    public LyricsRepository(Context c){this(c,new LrcClient());}LyricsRepository(Context c,LrcClient client){context=c.getApplicationContext();this.client=client;}
    public Result get(Track track,boolean online)throws Exception{return get(track,online,false);}
    public Result get(Track track,boolean online,boolean refresh) throws Exception {
        File manual=new File(context.getFilesDir(),"manual-lyrics/"+MusicCollections.key(track.uri)+".lrc");if(manual.isFile())try(InputStream in=new FileInputStream(manual)){return result(read(in),"직접 연결한 LRC");}
        if(!refresh&&!track.lrcUri.isEmpty()&&DriveUris.is(android.net.Uri.parse(track.lrcUri)))try(DriveDataSource source=new DriveDataSource(context)){source.open(new androidx.media3.datasource.DataSpec.Builder().setUri(track.lrcUri).build());ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=source.read(b,0,b.length))!=-1){if(out.size()+n>2_000_000)throw new IOException("가사 파일이 너무 큽니다.");out.write(b,0,n);}return result(out.toString(StandardCharsets.UTF_8.name()),"Google Drive LRC");}catch(IOException ignored){}
        if(!refresh&&!track.lrcUri.isEmpty()&&!DriveUris.is(android.net.Uri.parse(track.lrcUri)))try(InputStream in=context.getContentResolver().openInputStream(android.net.Uri.parse(track.lrcUri))){if(in!=null)return result(read(in),"로컬 LRC");}catch(IOException|SecurityException ignored){}
        File file=new File(context.getFilesDir(),"lyrics/"+key(track.uri)+".json");
        String cached="";JSONObject saved=null;
        if(file.exists())try{saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));cached=saved.getString("text");boolean timed=!Lrc.parse(cached).isEmpty();if(!refresh&&(timed||!online||saved.optBoolean("manual")||System.currentTimeMillis()-saved.optLong("syncCheck",0)<600000))return result(cached,timed?"LRCLIB · 실시간 가사 저장됨":"LRCLIB · 일반 가사 저장됨");}catch(Exception ignored){}
        if(!online){if(!cached.isEmpty())return result(cached,"LRCLIB · 저장됨");throw new IOException("온라인 가사 검색이 꺼져 있습니다. 로컬 LRC를 추가하거나 설정에서 켜 주세요.");}
        if(saved!=null){saved.put("syncCheck",System.currentTimeMillis());java.nio.file.Files.write(file.toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));}
        try{JSONObject row=client.find(track);String text=row.optBoolean("instrumental")?"연주곡입니다.":LrcClient.lyrics(row);if(text.isEmpty())throw new IOException("등록된 가사가 없습니다.");file.getParentFile().mkdirs();java.nio.file.Files.write(file.toPath(),new JSONObject().put("text",text).put("syncCheck",System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));return result(text,!Lrc.parse(text).isEmpty()?"LRCLIB · 실시간 가사":"LRCLIB · 일반 가사");}
        catch(Exception error){if(!cached.isEmpty())return result(cached,"LRCLIB · 저장된 가사 · 연결 확인 필요");throw error;}
    }
    public record Candidate(String title,String artist,String album,String text) {public boolean synced(){return !Lrc.parse(text).isEmpty();}}
    public List<Candidate> search(String query)throws Exception{org.json.JSONArray array=client.search(query);List<Candidate> out=new ArrayList<>();for(int i=0;i<Math.min(50,array.length());i++){JSONObject row=array.getJSONObject(i);String lyric=LrcClient.lyrics(row);if(!lyric.isEmpty())out.add(new Candidate(row.optString("trackName"),row.optString("artistName"),row.optString("albumName"),lyric));}out.sort(java.util.Comparator.comparing(Candidate::synced).reversed());return out;}
    public Result choose(Track track,Candidate candidate)throws Exception{File file=new File(context.getFilesDir(),"lyrics/"+key(track.uri)+".json");file.getParentFile().mkdirs();java.nio.file.Files.write(file.toPath(),new JSONObject().put("text",candidate.text()).put("manual",true).toString().getBytes(StandardCharsets.UTF_8));return result(candidate.text(),"LRCLIB · 직접 선택");}
    private Result result(String text,String source){return new Result(text,Lrc.parse(text),source);}
    private static String enc(String s) throws java.io.UnsupportedEncodingException {return URLEncoder.encode(s,StandardCharsets.UTF_8.toString());}
    private static String key(String s)throws Exception{return Hex.encode(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2_000_000)throw new IOException("가사 데이터가 너무 큽니다.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
}
