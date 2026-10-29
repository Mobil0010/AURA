package dev.aura.player;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same-folder photos; one query/decode per folder/version, never from a row bind on the UI thread. */
final class LocalFolderArtwork {
    record Photo(Uri uri,String name,long modified,long size) {}
    private static final Object LOCK=new Object();
    private static final android.util.LruCache<String,List<Photo>> FOLDERS=new android.util.LruCache<>(128);
    private static final android.util.LruCache<String,Boolean> INVALID=new android.util.LruCache<>(128);
    static List<Photo> candidates(List<Photo> photos){return photos.stream().filter(p->p.size()!=0&&p.size()<=DriveFolderArtwork.MAX_BYTES&&p.name().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")).sorted(Comparator.comparingInt((Photo p)->priority(p.name())).thenComparing(p->p.name().toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.toList());}
    private static int priority(String name){int dot=name.lastIndexOf('.');String stem=(dot<0?name:name.substring(0,dot)).toLowerCase(Locale.ROOT);int at=List.of("cover","folder","front","album","albumart","artwork").indexOf(stem);return at<0?100:at;}
    static boolean apply(Context c,String track,List<Photo> photos)throws Exception{synchronized(LOCK){for(Photo photo:photos){String key=Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest((photo.uri()+"|"+photo.modified()+"|"+photo.size()).getBytes(StandardCharsets.UTF_8)));if(INVALID.get(key)!=null)continue;File dir=new File(c.getCacheDir(),"local-folder-art"),cache=new File(dir,key+"-hq2.jpg");try{if(!cache.isFile()){dir.mkdirs();File download=File.createTempFile("photo-",".tmp",dir);try{try(InputStream in=c.getContentResolver().openInputStream(photo.uri());OutputStream out=new FileOutputStream(download)){if(in==null)throw new IOException("폴더 표지를 읽지 못했습니다.");byte[] buffer=new byte[32768];long received=0;int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();received+=n;if(received>DriveFolderArtwork.MAX_BYTES)throw new IOException("폴더 표지가 너무 큽니다.");out.write(buffer,0,n);}}var bitmap=ArtworkStore.file(download,ArtworkStore.MAX_EDGE);if(bitmap==null)throw new IOException("폴더 표지를 읽지 못했습니다.");try(OutputStream out=new FileOutputStream(cache)){if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,ArtworkStore.QUALITY,out))throw new IOException("폴더 표지를 저장하지 못했습니다.");}finally{bitmap.recycle();}}finally{download.delete();}}
                    File target=Library.artFile(c,track);target.getParentFile().mkdirs();ArtworkStore.linkOrCopy(cache,target);return true;
                }catch(InterruptedIOException cancelled){throw cancelled;}catch(Exception invalid){cache.delete();INVALID.put(key,true);}}}return false;}
    static boolean refresh(Context c,Track track)throws Exception{Uri uri=Uri.parse(track.uri);String parent="";if(!track.folderPath.isEmpty())parent=track.folderPath.get(track.folderPath.size()-1).id();if("file".equals(uri.getScheme())){File file=new File(uri.getPath());File directory=file.getParentFile();if(directory!=null)parent=Uri.fromFile(directory).toString();}if(parent.isEmpty())return false;String key=parent+"|"+Library.revision+"|"+LibraryScanService.state.revision();List<Photo> photos=FOLDERS.get(key);if(photos==null){List<Photo> found=new ArrayList<>();Uri folder=Uri.parse(parent);if("file".equals(folder.getScheme())){File[] files=new File(folder.getPath()).listFiles();if(files!=null)for(File file:files)if(file.isFile())found.add(new Photo(Uri.fromFile(file),file.getName(),file.lastModified(),file.length()));}
            else if("content".equals(folder.getScheme())){String id;try{id=DocumentsContract.getDocumentId(folder);}catch(IllegalArgumentException root){id=DocumentsContract.getTreeDocumentId(folder);}try(var cursor=c.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(folder,id),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(cursor!=null)while(cursor.moveToNext())found.add(new Photo(DocumentsContract.buildDocumentUriUsingTree(folder,cursor.getString(0)),cursor.getString(1)==null?"":cursor.getString(1),cursor.isNull(2)?0:cursor.getLong(2),cursor.isNull(3)?-1:cursor.getLong(3)));}}
            photos=List.copyOf(candidates(found));FOLDERS.put(key,photos);}return apply(c,track.uri,photos);}
}
