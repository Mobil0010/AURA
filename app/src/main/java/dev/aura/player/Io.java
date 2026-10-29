package dev.aura.player;
import java.io.*;
final class Io {
    static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>8_000_000)throw new IOException("입력 데이터가 너무 큽니다.");out.write(buffer,0,n);}return out.toByteArray();}
}
