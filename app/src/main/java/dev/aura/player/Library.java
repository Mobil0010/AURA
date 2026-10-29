package dev.aura.player;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import java.util.*;
import java.io.*;

public final class Library {
    static volatile long revision;
    private final Context context;private final Map<String,List<LocalFolderArtwork.Photo>> folderPhotos=new HashMap<>();
    public Library(Context context) { this.context=context.getApplicationContext(); }
    private static Set<String> embeddedExpected=Set.of();private static String coverMetadataKey="";
    static synchronized boolean expectsEmbedded(Context c,Track track){try{File file=new File(c.getFilesDir(),"library-metadata.json");String key=file.getPath()+"|"+file.lastModified()+"|"+file.length();if(!key.equals(coverMetadataKey)){var metadata=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));Set<String> expected=new HashSet<>();for(var keys=metadata.keys();keys.hasNext();){String id=keys.next();var row=metadata.optJSONObject(id);if(row!=null&&row.optBoolean("art"))expected.add(id);}embeddedExpected=Set.copyOf(expected);coverMetadataKey=key;}Uri uri=Uri.parse(track.uri);return embeddedExpected.contains(uri.getAuthority()+":"+DocumentsContract.getDocumentId(uri));}catch(Exception unavailable){return false;}}
    public List<Track> load(){List<Track> all=loadLocal();all.addAll(DriveLibrary.load(context));all.sort(Comparator.comparing(t->t.sortTitle));return all;}
    public List<Track> loadLocal() {
        try { JSONArray a=new JSONArray(new String(java.nio.file.Files.readAllBytes(file().toPath()),java.nio.charset.StandardCharsets.UTF_8)); List<Track> out=new ArrayList<>(); for(int i=0;i<a.length();i++)out.add(Track.from(a.getJSONObject(i))); return out; }
        catch(Exception e){ return new ArrayList<>(); }
    }
    private File file(){ return new File(context.getFilesDir(),"library.json"); }
    public record Progress(String phase,int completed,int total) {}
    private record Entry(Uri uri,String name,String folder,String lrc,long size,long modified,String key,List<FolderTree.Part> path) {}
    public static List<Uri> roots(Context c){var p=c.getSharedPreferences("aura",0);List<Uri> out=new ArrayList<>();try{JSONArray a=new JSONArray(p.getString("trees","[]"));for(int i=0;i<a.length();i++)out.add(Uri.parse(a.getString(i)));}catch(Exception ignored){}if(out.isEmpty()){String old=p.getString("tree","");if(!old.isEmpty())out.add(Uri.parse(old));}return out;}
    public static void saveRoots(Context c,List<Uri> roots){JSONArray a=new JSONArray();for(Uri u:new LinkedHashSet<>(roots))a.put(u.toString());c.getSharedPreferences("aura",0).edit().putString("trees",a.toString()).remove("tree").apply();}
    public List<Track> scan(Uri tree,java.util.function.IntConsumer progress)throws Exception{return scan(List.of(tree),p->progress.accept(p.completed()));}
    public List<Track> scan(List<Uri> roots,java.util.function.Consumer<Progress> progress)throws Exception{return scan(roots,progress,false);}
    public List<Track> scan(List<Uri> roots,java.util.function.Consumer<Progress> progress,boolean additionsOnly)throws Exception{return scanInternal(roots,progress,additionsOnly,null);}
    List<Track> scanFolder(FolderScope scope,java.util.function.Consumer<Progress> progress)throws Exception{if(scope.drive()||!roots(context).contains(Uri.parse(scope.root())))throw new IOException("등록된 기기 음악 폴더를 선택해 주세요.");return scanInternal(List.of(Uri.parse(scope.root())),progress,false,scope);}
    private List<Track> scanInternal(List<Uri> roots,java.util.function.Consumer<Progress> progress,boolean additionsOnly,FolderScope scope)throws Exception{
        if(roots.isEmpty())throw new IOException("음악 폴더를 추가해 주세요.");
        folderPhotos.clear();Map<String,Entry> entries=new LinkedHashMap<>();Set<String> visited=new HashSet<>();
        if(scope!=null)walk(Uri.parse(scope.root()),scope.id(),scope.path().stream().skip(1).map(FolderTree.Part::name).collect(java.util.stream.Collectors.joining("/")),entries,visited,progress,0,scope.path());else for(Uri tree:roots)walk(tree,DocumentsContract.getTreeDocumentId(tree),"",entries,visited,progress,0,List.of(new FolderTree.Part(tree.toString(),rootName(tree))));
        org.json.JSONObject old=new org.json.JSONObject();File cache=new File(context.getFilesDir(),"library-metadata.json");try{old=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        org.json.JSONObject next=scope==null?new org.json.JSONObject():new org.json.JSONObject(old.toString());List<Track> result=new ArrayList<>();List<Track> previous=additionsOnly?loadLocal():List.of();Map<String,Track> known=new HashMap<>();for(Track track:previous)try{Uri uri=Uri.parse(track.uri);known.put(uri.getAuthority()+":"+DocumentsContract.getDocumentId(uri),track);}catch(Exception ignored){}boolean layoutChanged=false;List<Entry> work=new ArrayList<>();if(scope!=null)for(Track saved:loadLocal())if(scope.contains(saved))try{Uri uri=Uri.parse(saved.uri);next.remove(uri.getAuthority()+":"+DocumentsContract.getDocumentId(uri));}catch(Exception ignored){}
        for(Entry entry:entries.values()){Track saved=known.get(entry.key());if(additionsOnly&&saved!=null){boolean same=saved.uri.equals(entry.uri().toString())&&saved.folder.equals(entry.folder())&&saved.lrcUri.equals(entry.lrc())&&saved.folderPath.equals(entry.path());Track track=same?saved:new Track(entry.uri().toString(),saved.title,saved.artist,saved.album,entry.folder(),saved.duration,entry.lrc(),saved.kind).withGenre(saved.genre).withNumbers(saved.trackNumber,saved.discNumber).withPath(entry.path());layoutChanged|=!same;result.add(track);org.json.JSONObject hit=old.optJSONObject(entry.key());if(hit!=null)next.put(entry.key(),hit);continue;}work.add(entry);}
        if(additionsOnly&&work.isEmpty()&&!layoutChanged&&result.size()==previous.size()){progress.accept(new Progress("새 음악이 없습니다",0,0));return previous;}
        int totalWork=work.size();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(PlaybackBudget.get(context).scanThreads());
        java.util.concurrent.CompletionService<java.util.Map.Entry<Entry,Track>> completion=new java.util.concurrent.ExecutorCompletionService<>(pool);
        Iterator<Entry> pending=work.iterator();int active=0,done=0;
        try{while(pending.hasNext()||active>0){
            while(pending.hasNext()&&active<4){Entry entry=pending.next();org.json.JSONObject hit=old.optJSONObject(entry.key());
                Track cached=null;if(scope==null&&!additionsOnly&&hit!=null&&entry.modified()>0&&entry.size()>=0&&hit.optLong("modified",-1)==entry.modified()&&hit.optLong("size",-1)==entry.size()&&(!hit.optBoolean("art",false)||artFile(context,entry.uri().toString()).isFile()))try{Track t=Track.from(hit.getJSONObject("track"));if(t.duration>0&&hit.getJSONObject("track").has("trackNumber")&&hit.getJSONObject("track").has("genre"))cached=new Track(entry.uri().toString(),t.title,t.artist,t.album,entry.folder(),t.duration,entry.lrc(),t.kind).withGenre(t.genre).withNumbers(t.trackNumber,t.discNumber);}catch(Exception ignored){}
                if(cached!=null){Track track=cached.withPath(entry.path());if(!hit.optBoolean("art",false))try{LocalFolderArtwork.apply(context,track.uri,folderPhotos.getOrDefault(entry.key(),List.of()));}catch(Exception optionalPhoto){}result.add(track);next.put(entry.key(),new org.json.JSONObject().put("modified",entry.modified()).put("size",entry.size()).put("track",track.json()).put("art",artFile(context,track.uri).isFile()));progress.accept(new Progress("저장한 음악 정보 확인 중",++done,totalWork));continue;}completion.submit(()->Map.entry(entry,read(entry).withPath(entry.path())));active++;
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(active==0)continue;var item=completion.take().get();active--;Entry entry=item.getKey();Track track=item.getValue();result.add(track);
            next.put(entry.key(),new org.json.JSONObject().put("modified",entry.modified()).put("size",entry.size()).put("track",track.json()).put("art",artFile(context,track.uri).isFile()));
            progress.accept(new Progress(additionsOnly?"새 음악 정보 읽는 중":"음악 정보 읽는 중",++done,totalWork));
        }}finally{pool.shutdownNow();}
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(scope!=null){if(!roots(context).contains(Uri.parse(scope.root())))throw new IOException("음악 폴더 설정이 변경되었습니다.");LinkedHashMap<String,Track> merged=new LinkedHashMap<>();for(Track saved:loadLocal())if(!scope.contains(saved))merged.put(saved.uri,saved);for(Track refreshed:result)merged.put(refreshed.uri,refreshed);result=new ArrayList<>(merged.values());}result.sort(Comparator.comparing(t->t.sortTitle));JSONArray a=new JSONArray();for(Track t:result)a.put(t.json());
        // A failed or cancelled scan leaves the previous library intact.
        atomic(cache,next.toString());atomic(file(),a.toString());revision++;return result;
    }
    private void atomic(File target,String content)throws IOException{File tmp=new File(target.getPath()+".tmp");java.nio.file.Files.write(tmp.toPath(),content.getBytes(java.nio.charset.StandardCharsets.UTF_8));java.nio.file.Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    private String rootName(Uri tree){try(Cursor cursor=context.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))return cursor.getString(0);}catch(Exception ignored){}String id=DocumentsContract.getTreeDocumentId(tree);String name=id.substring(Math.max(id.lastIndexOf('/'),id.lastIndexOf(':'))+1);return name.isEmpty()?"기기 저장소":name;}
    private void walk(Uri tree,String parent,String path,Map<String,Entry> out,Set<String> visited,java.util.function.Consumer<Progress> progress,int depth,List<FolderTree.Part> trail)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(depth>64)throw new IOException("폴더 깊이가 너무 깊습니다.");
        String directory=tree.getAuthority()+":"+parent;if(!visited.add(directory))return;
        List<String[]> rows=new ArrayList<>();Map<String,String> sidecars=new HashMap<>();
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),columns,null,null,null)){
            if(c==null)throw new IOException("폴더를 읽을 수 없습니다. 접근 권한을 확인해 주세요.");
            while(c.moveToNext()){String id=c.getString(0),name=c.getString(1);if(name==null)name=id;String[] r={id,name,c.getString(2),c.isNull(3)?"-1":c.getString(3),c.isNull(4)?"0":c.getString(4)};rows.add(r);if(name.toLowerCase(Locale.ROOT).endsWith(".lrc"))sidecars.put(stem(name).toLowerCase(Locale.ROOT),DocumentsContract.buildDocumentUriUsingTree(tree,id).toString());}
        }
        List<LocalFolderArtwork.Photo> photos=new ArrayList<>();for(String[] row:rows)if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(row[2]))photos.add(new LocalFolderArtwork.Photo(DocumentsContract.buildDocumentUriUsingTree(tree,row[0]),row[1],Long.parseLong(row[4]),Long.parseLong(row[3])));List<LocalFolderArtwork.Photo> cover=List.copyOf(LocalFolderArtwork.candidates(photos));
        for(String[] r:rows){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(DocumentsContract.Document.MIME_TYPE_DIR.equals(r[2])){List<FolderTree.Part> child=new ArrayList<>(trail);child.add(new FolderTree.Part(DocumentsContract.buildDocumentUriUsingTree(tree,r[0]).toString(),r[1]));walk(tree,r[0],path.isEmpty()?r[1]:path+"/"+r[1],out,visited,progress,depth+1,child);continue;}
            if(!(r[2]!=null&&r[2].startsWith("audio/"))&&!r[1].toLowerCase(Locale.ROOT).matches(".*\\.(flac|wav|mp3|m4a|aac|ogg|opus|aiff|alac|dsf|dff)$"))continue;
            String key=tree.getAuthority()+":"+r[0];if(!cover.isEmpty())folderPhotos.put(key,cover);out.putIfAbsent(key,new Entry(DocumentsContract.buildDocumentUriUsingTree(tree,r[0]),r[1],path.isEmpty()?"선택한 폴더":path,sidecars.getOrDefault(stem(r[1]).toLowerCase(Locale.ROOT),""),Long.parseLong(r[3]),Long.parseLong(r[4]),key,List.copyOf(trail)));
        }
        progress.accept(new Progress("폴더 확인 중",out.size(),-1));
    }
    private Track read(Entry e)throws Exception{
        String title=stem(e.name()),artist="알 수 없는 아티스트",album="알 수 없는 앨범";long duration=0;int trackNumber=0,discNumber=0;
        boolean embedded=false;String genre="";MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(context,e.uri());genre=value(m,MediaMetadataRetriever.METADATA_KEY_GENRE,"");title=value(m,MediaMetadataRetriever.METADATA_KEY_TITLE,title);artist=value(m,MediaMetadataRetriever.METADATA_KEY_ARTIST,artist);album=value(m,MediaMetadataRetriever.METADATA_KEY_ALBUM,album);duration=Long.parseLong(value(m,MediaMetadataRetriever.METADATA_KEY_DURATION,"0"));trackNumber=TrackOrder.number(value(m,MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,"0"));discNumber=TrackOrder.number(value(m,MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,"0"));
            byte[] picture=m.getEmbeddedPicture();if(picture==null)artFile(context,e.uri().toString()).delete();if(picture!=null&&picture.length<=ArtworkStore.MAX_EMBEDDED_BYTES){android.graphics.Bitmap bitmap=thumbnail(picture);if(bitmap!=null){try{ArtworkStore.save(context,e.uri().toString(),bitmap);embedded=true;}finally{bitmap.recycle();}}}
        }catch(Exception ignored){}finally{try{m.release();}catch(Exception ignored){}}
        if(!embedded)try{LocalFolderArtwork.apply(context,e.uri().toString(),folderPhotos.getOrDefault(e.key(),List.of()));}catch(Exception optionalFolderPhoto){}
        String lower=e.name().toLowerCase(Locale.ROOT);return new Track(e.uri().toString(),title,artist,album,e.folder(),duration,e.lrc(),lower.endsWith(".dsf")?"dsf":lower.endsWith(".dff")?"dff":"").withGenre(genre).withNumbers(trackNumber,discNumber);
    }
    public static android.graphics.Bitmap thumbnail(byte[] picture){return ArtworkStore.thumbnail(picture);}
    public static File artFile(Context c,String uri){try{return new File(c.getCacheDir(),"art/"+Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+".jpg");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String stem(String s){int p=s.lastIndexOf('.');return p>0?s.substring(0,p):s;}
    private static String value(MediaMetadataRetriever m,int key,String fallback){String v=m.extractMetadata(key);return v==null||v.isBlank()?fallback:v;}
}
