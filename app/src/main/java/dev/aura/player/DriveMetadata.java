package dev.aura.player;

import android.media.MediaDataSource;import android.net.Uri;
import androidx.media3.common.C;import androidx.media3.datasource.DataSpec;
import java.io.*;import java.util.*;

/** Random reads for tags/art, bounded to 1 MiB RAM and 4 MiB transferred per track. */
@androidx.media3.common.util.UnstableApi
final class DriveMetadata extends MediaDataSource {
    private static final int BLOCK=256*1024,BUDGET=4*1024*1024;
    private final Thread owner=Thread.currentThread();private final DriveHttp http;private final Uri uri;private long size;private int fetched;private boolean closed;
    private final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<>(8,.75f,true);
    DriveMetadata(DriveHttp http,Uri uri,long size){this.http=http;this.uri=uri;this.size=size;}
    @Override public synchronized long getSize()throws IOException{if(size<0){try(DriveDataSource source=new DriveDataSource(http,u->true)){source.open(new DataSpec.Builder().setUri(uri).setLength(1).build());String range=source.getResponseHeaders().entrySet().stream().filter(e->e.getKey()!=null&&e.getKey().equalsIgnoreCase("Content-Range")).flatMap(e->e.getValue().stream()).findFirst().orElse("");int slash=range.lastIndexOf('/');if(slash<0)throw new IOException("파일 크기를 확인할 수 없습니다.");size=Long.parseLong(range.substring(slash+1));}}return size;}
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int count)throws IOException{if(owner.isInterrupted())throw new InterruptedIOException();if(closed)throw new IOException("읽기가 종료되었습니다.");if(position<0||offset<0||count<0||offset>buffer.length-count)throw new IOException("잘못된 파일 읽기 구간입니다.");if(count==0)return 0;if(position>=getSize())return C.RESULT_END_OF_INPUT;long block=position/BLOCK;byte[] bytes=cache.get(block);if(bytes==null){int wanted=(int)Math.min(BLOCK,size-block*BLOCK);if(fetched+wanted>BUDGET)throw new IOException("곡 정보 읽기 한도에 도달했습니다.");fetched+=wanted;bytes=new byte[wanted];try(DriveDataSource source=new DriveDataSource(http,u->true)){source.open(new DataSpec.Builder().setUri(uri).setPosition(block*BLOCK).setLength(wanted).build());int at=0;while(at<wanted){int n=source.read(bytes,at,wanted-at);if(n<0)throw new EOFException();at+=n;}}cache.put(block,bytes);if(cache.size()>4)cache.remove(cache.keySet().iterator().next());}int from=(int)(position-block*BLOCK),n=Math.min(count,bytes.length-from);System.arraycopy(bytes,from,buffer,offset,n);return n;}
    @Override public synchronized void close(){closed=true;cache.clear();}
}
