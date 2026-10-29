package dev.aura.player;
import android.content.*;import org.json.*;import java.util.*;
/** Relink unique song identities after users re-grant folders on a different device. Worker only. */
final class BackupLinks {
 static String identity(Track t){return MusicCollections.duplicateKey(t)+"|"+MusicCollections.normalize(t.album)+"|"+t.duration;}
 static void apply(Context c,List<Track> tracks)throws Exception{
  var prefs=MusicCollections.prefs(c);String pending=prefs.getString("pending-links","");if(pending.isEmpty())return;JSONArray source=new JSONArray(pending),remaining=new JSONArray();Map<String,Track> byIdentity=new HashMap<>();Set<String> ambiguous=new HashSet<>(),uris=new HashSet<>();for(Track t:tracks){uris.add(t.uri);String key=identity(t);if(byIdentity.putIfAbsent(key,t)!=null)ambiguous.add(key);}
  Map<String,String> links=new HashMap<>();for(int i=0;i<source.length();i++){Track old=Track.from(source.getJSONObject(i));if(uris.contains(old.uri))continue;Track target=byIdentity.get(identity(old));if(target!=null&&!ambiguous.contains(identity(old)))links.put(old.uri,target.uri);else remaining.put(source.getJSONObject(i));}
  if(!links.isEmpty()){
   var edit=prefs.edit();for(String kind:List.of("favorites","hidden")){Set<String> set=new HashSet<>();for(String uri:prefs.getStringSet(kind,Set.of()))set.add(links.getOrDefault(uri,uri));edit.putStringSet(kind,set);}
   for(var link:links.entrySet()){String from=MusicCollections.key(link.getKey()),to=MusicCollections.key(link.getValue());for(String prefix:List.of("bookmarks:","lyric-offset:")){Object value=prefs.getAll().get(prefix+from);if(value instanceof String)edit.putString(prefix+to,(String)value);else if(value instanceof Long)edit.putLong(prefix+to,(Long)value);edit.remove(prefix+from);}}
   var aura=c.getSharedPreferences("aura",0);JSONObject lists=new JSONObject(aura.getString("playlists","{}"));for(var keys=lists.keys();keys.hasNext();){String name=keys.next();JSONArray rows=lists.getJSONArray(name);for(int i=0;i<rows.length();i++)if(links.containsKey(rows.getString(i)))rows.put(i,links.get(rows.getString(i)));}var ae=aura.edit().putString("playlists",lists.toString());String last=aura.getString("lastTrack","");if(links.containsKey(last))ae.putString("lastTrack",links.get(last));ae.commit();edit.commit();
   var db=ListeningStore.get(c).getWritableDatabase();db.beginTransaction();try{for(var link:links.entrySet())db.execSQL("UPDATE heard SET uri=? WHERE uri=?",new Object[]{link.getValue(),link.getKey()});db.setTransactionSuccessful();}finally{db.endTransaction();}
  }
  prefs.edit().putString("pending-links",remaining.toString()).commit();
 }
}
