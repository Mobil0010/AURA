package dev.aura.player;

import static org.junit.Assert.*;import org.junit.*;import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import org.json.*;

@RunWith(AndroidJUnit4.class)
public class LyricsApiTest {
    private Track track(long duration){return new Track("content://lyrics-test/"+UUID.randomUUID(),"Test Song","Test Artist","알 수 없는 앨범","",duration,"");}
    @Test public void unknownDurationIsOmittedAndCurrentUserAgentIsSent()throws Exception{try(Server server=new Server()){
        server.exact="{\"syncedLyrics\":\"[00:00.00]Synthetic test line\"}";var row=new LrcClient(server.base()).find(track(0));assertFalse(LrcClient.lyrics(row).isEmpty());String request=server.requests.get(0);assertFalse(request.contains("duration="));assertFalse(request.contains("album_name="));assertTrue(request.contains("User-Agent: AURA/"+BuildConfig.VERSION_NAME));}}
    @Test public void missingExactAlbumFallsBackToMatchingTitleArtistAndDuration()throws Exception{try(Server server=new Server()){
        server.code=404;server.results="[{\"trackName\":\"Test Song\",\"artistName\":\"Other Artist\",\"duration\":180,\"syncedLyrics\":\"[00:00.00]Wrong\"},{\"trackName\":\"Test Song\",\"artistName\":\"Test Artist\",\"duration\":181,\"syncedLyrics\":\"[00:00.00]Correct synthetic line\"}]";
        var row=new LrcClient(server.base()).find(track(180000));assertTrue(LrcClient.lyrics(row).contains("Correct"));assertEquals(2,server.requests.size());}}
    @Test public void mismatchedDurationDoesNotSilentlySelectAnotherVersion()throws Exception{try(Server server=new Server()){
        server.code=404;server.results="[{\"trackName\":\"Test Song\",\"artistName\":\"Test Artist\",\"duration\":220,\"syncedLyrics\":\"[00:00.00]Other version\"}]";
        try{new LrcClient(server.base()).find(track(180000));fail("Wrong version selected");}catch(IOException expected){assertTrue(expected.getMessage().contains("일치"));}}}
    @Test public void nullSynchronizedLyricsUsePlainTextAndRateLimitIsActionable()throws Exception{try(Server server=new Server()){
        server.exact="{\"syncedLyrics\":null,\"plainLyrics\":\"Synthetic unsynced line\"}";assertEquals("Synthetic unsynced line",LrcClient.lyrics(new LrcClient(server.base()).find(track(10000))));server.code=429;try{new LrcClient(server.base()).find(track(10000));fail("Rate limit ignored");}catch(IOException expected){assertTrue(expected.getMessage().contains("제한"));}}}
    @Test public void optOutDoesNotRequestAndSelectedLyricsRemainAvailableOffline()throws Exception{try(Server server=new Server()){
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();var repository=new LyricsRepository(context,new LrcClient(server.base()));Track track=track(0);try{repository.get(track,false);fail("Online opt-out ignored");}catch(IOException expected){}assertEquals(0,server.requests.size());repository.choose(track,new LyricsRepository.Candidate(track.title,track.artist,track.album,"[00:00.00]Saved synthetic line"));assertEquals(1,repository.get(track,false).lines().size());assertEquals(0,server.requests.size());}}
    @Test public void exactPlainLyricsCanUpgradeToSynchronizedSearchResult()throws Exception{try(Server server=new Server()){
        server.exact="{\"plainLyrics\":\"Plain synthetic lyric\",\"syncedLyrics\":null}";
        server.results="[{\"trackName\":\"Test Song\",\"artistName\":\"Test Artist\",\"duration\":180,\"syncedLyrics\":\"[00:00.00]First synthetic line\\n[00:01.00]Next synthetic line\"}]";
        assertEquals(2,Lrc.parse(LrcClient.lyrics(new LrcClient(server.base()).find(track(180000)))).size());assertEquals(2,server.requests.size());}}
    @Test public void oldPlainCacheUpgradesAndExplicitRefreshCanReplaceManualPlainLyrics()throws Exception{try(Server server=new Server()){
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();Track track=track(180000);var repository=new LyricsRepository(context,new LrcClient(server.base()));
        String key=Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(track.uri.getBytes(StandardCharsets.UTF_8)));File cache=new File(context.getFilesDir(),"lyrics/"+key+".json");cache.getParentFile().mkdirs();java.nio.file.Files.write(cache.toPath(),new JSONObject().put("text","Old plain synthetic lyric").toString().getBytes(StandardCharsets.UTF_8));
        server.exact="{\"syncedLyrics\":\"[00:00.00]First synthetic line\\n[00:01.00]Next synthetic line\"}";
        assertEquals(2,repository.get(track,true).lines().size());repository.choose(track,new LyricsRepository.Candidate(track.title,track.artist,track.album,"Chosen plain synthetic lyric"));int requests=server.requests.size();assertEquals(0,repository.get(track,true).lines().size());assertEquals(requests,server.requests.size());assertEquals(2,repository.get(track,true,true).lines().size());int done=server.requests.size();assertEquals(2,repository.get(track,false).lines().size());assertEquals(done,server.requests.size());cache.delete();}}
    static class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));final ExecutorService worker=Executors.newSingleThreadExecutor();final List<String> requests=new CopyOnWriteArrayList<>();volatile int code=200;volatile String exact="{}",results="[]";
        Server()throws Exception{worker.execute(()->{while(!socket.isClosed())try(Socket client=socket.accept()){var reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));String first=reader.readLine();StringBuilder request=new StringBuilder(first);String line;while((line=reader.readLine())!=null&&!line.isEmpty())request.append('\n').append(line);requests.add(request.toString());boolean search=first.contains("/search?");int status=search?200:code;byte[] bytes=(search?results:exact).getBytes(StandardCharsets.UTF_8);var out=client.getOutputStream();out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);}catch(Exception ignored){}});}
        String base(){return "http://127.0.0.1:"+socket.getLocalPort()+"/";}public void close()throws Exception{socket.close();worker.shutdownNow();}
    }
}
