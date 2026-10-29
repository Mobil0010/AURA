package dev.aura.player;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Optional same-folder cover. Transfers are bounded; one thumbnail is reused across all songs. */
final class DriveFolderArtwork {
    private final boolean upgrade;private final Set<String> unavailable=new HashSet<>();
    static final int MAX_BYTES=16*1024*1024;private boolean changed;boolean changed(){return changed;}
    private final Context context;private final DriveHttp http;private final DriveAuth auth;private final long generation;private final String account;
    DriveFolderArtwork(Context c,DriveHttp http,long generation){this(c,http,generation,false);}
    DriveFolderArtwork(Context c,DriveHttp http,long generation,boolean upgrade){this.upgrade=upgrade;context=c;this.http=http;auth=DriveAuth.get(c);this.generation=generation;account=auth.accountId();}
    static List<DriveApi.Item> candidates(List<DriveApi.Item> items){return items.stream().filter(i->i.downloadable()&&!i.folder()&&i.size()!=0&&i.size()<=MAX_BYTES&&i.name().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")).sorted(Comparator.comparingInt((DriveApi.Item i)->priority(i.name())).thenComparing(i->i.name().toLowerCase(Locale.ROOT)).thenComparing(DriveApi.Item::id)).collect(java.util.stream.Collectors.toList());}
    static DriveApi.Item choose(List<DriveApi.Item> items){var options=candidates(items);return options.isEmpty()?null:options.get(0);}
    void apply(JSONObject row,List<DriveApi.Item> images)throws Exception{if(images.isEmpty()){apply(row,(DriveApi.Item)null);return;}for(var image:images)try{apply(row,image);if(row.optBoolean("art")&&Library.artFile(context,row.getString("uri")).isFile())return;}catch(InterruptedIOException cancelled){throw cancelled;}catch(Exception optional){active();}}
    private static int priority(String name){int dot=name.lastIndexOf('.');String stem=(dot<0?name:name.substring(0,dot)).toLowerCase(Locale.ROOT);int at=List.of("cover","folder","front","album","albumart","artwork").indexOf(stem);return at<0?100:at;}
    private void active()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(generation!=auth.generation()||!account.equals(auth.accountId()))throw new IOException("Google Drive 연결이 변경되었습니다.");}
    private static File directory(Context c){return new File(c.getFilesDir(),"drive-folder-art");}
    static void clear(Context c){File[] files=directory(c).listFiles();if(files!=null)for(File file:files)file.delete();}
    private String key(DriveApi.Item item)throws Exception{return Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest((account+"|"+item.id()+"|"+item.modified()+"|"+item.size()).getBytes(StandardCharsets.UTF_8)));}
    void apply(JSONObject row,DriveApi.Item image)throws Exception{
        boolean folder=row.optString("artKind").equals("folder");active();File destination=Library.artFile(context,row.getString("uri"));if(row.optBoolean("art")&&!folder&&destination.isFile())return;
        if(image==null){if(folder)synchronized(DriveLibrary.LOCK){active();changed|=destination.delete();row.put("art",false).put("artKind","none").remove("artKey");}return;}
        String key=key(image);if(unavailable.contains(key))return;if(folder&&key.equals(row.optString("artKey"))&&destination.isFile()&&(!upgrade||ArtworkStore.current(context,row.getString("uri"))))return;
        File cache=new File(directory(context),key+"-hq2.jpg");try{if(!cache.isFile()){
            File download=new File(directory(context),key+".download");directory(context).mkdirs();Bitmap bitmap;try{try(DriveHttp.Response response=http.open("files/"+DriveUris.id(image.id())+"?alt=media&supportsAllDrives=true",0,MAX_BYTES+1L,null);OutputStream out=new FileOutputStream(download)){byte[] buffer=new byte[32768];long received=0;int n;while((n=response.input().read(buffer))!=-1){active();received+=n;if(received>MAX_BYTES)throw new IOException("폴더 표지가 너무 큽니다.");out.write(buffer,0,n);}}active();bitmap=ArtworkStore.file(download,ArtworkStore.MAX_EDGE);if(bitmap==null)throw new IOException("폴더 표지 이미지를 읽지 못했습니다.");}finally{download.delete();}
            File temp=new File(directory(context),key+".tmp");try{directory(context).mkdirs();try(OutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,ArtworkStore.QUALITY,out))throw new IOException("폴더 표지를 저장하지 못했습니다.");}synchronized(DriveLibrary.LOCK){active();Files.move(temp.toPath(),cache.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{bitmap.recycle();temp.delete();}
        }
        synchronized(DriveLibrary.LOCK){active();destination.getParentFile().mkdirs();ArtworkStore.linkOrCopy(cache,destination);changed=true;row.put("art",true).put("artKind","folder").put("artKey",key);}}catch(Exception error){unavailable.add(key);throw error;}
    }
}
