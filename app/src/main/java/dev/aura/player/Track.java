package dev.aura.player;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import org.json.JSONObject;

public final class Track {
    public final String uri, title, artist, album, folder, lrcUri;
    public final String kind,folderId,mime;
    public final String genre;
    private final boolean cloud,tagsReady;
    public final long duration;public final int trackNumber,discNumber,orderTrack,orderDisc;
    public final String sortTitle,searchKey;public final java.util.List<FolderTree.Part> folderPath;
    public Track(String uri, String title, String artist, String album, String folder, long duration, String lrcUri) {
        this(uri,title,artist,album,folder,duration,lrcUri, "");
    }
    public Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind){
        this(uri,title,artist,album,folder,duration,lrcUri,kind,folder,"");
    }
    public Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind,String folderId,String mime){
        this(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,java.util.List.of());
    }
    private Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind,String folderId,String mime,java.util.List<FolderTree.Part> path){
        this(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,path,false);
    }
    private Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind,String folderId,String mime,java.util.List<FolderTree.Part> path,boolean tagsReady){this(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,path,tagsReady,0,0);}
    private Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind,String folderId,String mime,java.util.List<FolderTree.Part> path,boolean tagsReady,int trackNumber,int discNumber){this(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,path,tagsReady,trackNumber,discNumber,"");}
    private Track(String uri,String title,String artist,String album,String folder,long duration,String lrcUri,String kind,String folderId,String mime,java.util.List<FolderTree.Part> path,boolean tagsReady,int trackNumber,int discNumber,String genre){this.genre=genre==null?"":genre.trim();this.trackNumber=Math.max(0,trackNumber);this.discNumber=Math.max(0,discNumber);int[] fileNumbers=TrackOrder.fileNumbers(uri);orderTrack=this.trackNumber>0?this.trackNumber:fileNumbers[1];orderDisc=this.discNumber>0?this.discNumber:fileNumbers[0];this.tagsReady=tagsReady;
        this.sortTitle=title.toLowerCase(java.util.Locale.ROOT);this.folderPath=java.util.List.copyOf(path);this.cloud=DriveUris.is(Uri.parse(uri));this.folderId=folderId;this.mime=mime;this.searchKey=(title+" "+album+" "+artist+" "+folder).toLowerCase(java.util.Locale.ROOT);this.kind=kind;this.uri=uri; this.title=title; this.artist=artist; this.album=album; this.folder=folder; this.duration=duration; this.lrcUri=lrcUri;
    }
    Track withPath(java.util.List<FolderTree.Part> path){return new Track(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,path,tagsReady,trackNumber,discNumber,genre);}
    Track withNumbers(int track,int disc){return new Track(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,folderPath,tagsReady,track,disc,genre);}
    Track withGenre(String genre){return new Track(uri,title,artist,album,folder,duration,lrcUri,kind,folderId,mime,folderPath,tagsReady,trackNumber,discNumber,genre);}
    public boolean drive(){return cloud;}
    public String sourceLabel(){return drive()?"Google Drive":"기기 저장소";}
    public String folderKey(){return sourceLabel()+"\u0000"+(drive()?Uri.parse(uri).getAuthority()+"/"+folderId:folder);}
    public MediaItem item() {
        android.os.Bundle extras=tagsReady?new android.os.Bundle():null;if(extras!=null)extras.putBoolean("auraTagsReady",true);return new MediaItem.Builder().setMediaId(uri).setUri(kind.equals("dsf")||kind.equals("dff")?new Uri.Builder().scheme("aura-dsd").authority("stream").appendQueryParameter("source",uri).build().toString():uri).setMimeType(mime.isEmpty()?null:mime).setMediaMetadata(new MediaMetadata.Builder()
                .setGenre(genre.isEmpty()?null:genre).setTrackNumber(trackNumber>0?trackNumber:null).setDiscNumber(discNumber>0?discNumber:null).setTitle(title).setArtist(artist).setAlbumTitle(album).setIsPlayable(true).setExtras(extras).build()).build();
    }
    public JSONObject json() throws Exception {
        org.json.JSONArray path=new org.json.JSONArray();for(var part:folderPath)path.put(new JSONObject().put("id",part.id()).put("name",part.name()));return new JSONObject().put("genre",genre).put("trackNumber",trackNumber).put("discNumber",discNumber).put("path",path).put("uri",uri).put("title",title).put("artist",artist).put("album",album).put("folder",folder).put("duration",duration).put("lrc",lrcUri).put("kind",kind).put("folderId",folderId).put("mime",mime).put("enriched",tagsReady);
    }
    public static Track from(JSONObject j) throws Exception {
        java.util.List<FolderTree.Part> path=new java.util.ArrayList<>();org.json.JSONArray array=j.optJSONArray("path");if(array!=null)for(int i=0;i<array.length();i++){JSONObject part=array.getJSONObject(i);path.add(new FolderTree.Part(part.getString("id"),part.getString("name")));}return new Track(j.getString("uri"),j.getString("title"),j.getString("artist"),j.getString("album"),j.getString("folder"),j.getLong("duration"),j.optString("lrc"),j.optString("kind"),j.optString("folderId",j.getString("folder")),j.optString("mime"),path,j.optBoolean("enriched",false),j.optInt("trackNumber"),j.optInt("discNumber"),j.optString("genre"));
    }
}
