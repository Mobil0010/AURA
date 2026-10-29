package dev.aura.player;
import android.net.Uri;
import java.io.IOException;

final class DriveUris {
    static final String SCHEME="aura-drive";
    static boolean is(Uri uri){return SCHEME.equals(uri.getScheme());}
    static String id(String id)throws IOException{if(id==null||!id.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("Google Drive 파일 정보가 올바르지 않습니다.");return id;}
    static Uri file(String account,String file,String name)throws IOException{return new Uri.Builder().scheme(SCHEME).authority(id(account)).appendPath(id(file)).appendQueryParameter("name",name).build();}
    static String fileId(Uri uri)throws IOException{if(!is(uri)||uri.getPathSegments().size()!=1)throw new IOException("Google Drive 파일 주소가 올바르지 않습니다.");return id(uri.getLastPathSegment());}
}
