package dev.aura.player;
import android.content.Context;import android.net.Uri;import androidx.media3.datasource.*;import java.io.*;import java.util.*;
@androidx.media3.common.util.UnstableApi
public final class DsdDataSource implements DataSource,AutoCloseable {
    private final Context context;private final DataSource delegate;private final DriveDataSource drive;private DataSource active;
    public DsdDataSource(Context c){context=c;delegate=new DefaultDataSource.Factory(c).createDataSource();drive=new DriveDataSource(c);active=delegate;}
    @Override public long open(DataSpec spec)throws IOException{try{java.io.File offline=DriveUris.is(spec.uri)?OfflineMusic.available(context,spec.uri.toString()):null;if(offline!=null){offline.setLastModified(System.currentTimeMillis());active=delegate;return delegate.open(spec.buildUpon().setUri(Uri.fromFile(offline)).build());}active=DriveUris.is(spec.uri)?drive:delegate;if(active==drive)return drive.open(spec);if("aura-dsd".equals(spec.uri.getScheme())){Uri source=Uri.parse(spec.uri.getQueryParameter("source"));File saved=DriveUris.is(source)?OfflineMusic.available(context,source.toString()):null;File pcm=Dsd.prepare(context,saved==null?source:Uri.fromFile(saved),AudioSettings.current.dsd());return delegate.open(spec.buildUpon().setUri(Uri.fromFile(pcm)).build());}return delegate.open(spec);}catch(IOException e){throw e;}catch(Exception e){throw new IOException(e.getMessage(),e);}}
    @Override public int read(byte[] buffer,int offset,int size)throws IOException{return active.read(buffer,offset,size);}
    @Override public Uri getUri(){return active.getUri();}
    @Override public Map<String,List<String>> getResponseHeaders(){return active.getResponseHeaders();}
    @Override public void close()throws IOException{active.close();}
    @Override public void addTransferListener(TransferListener listener){delegate.addTransferListener(listener);drive.addTransferListener(listener);}
}
