package dev.aura.player;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;

/** Indexed minute buckets; all disk access uses one background executor. No audio data is stored. */
final class ListeningStore extends SQLiteOpenHelper {
    private static ListeningStore instance;
    static synchronized ListeningStore get(Context c){if(instance==null)instance=new ListeningStore(c,"listening.db");return instance;}
    final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"AURA-listening"));
    ListeningStore(Context c,String name){super(c.getApplicationContext(),name,null,2);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE heard (visit TEXT NOT NULL, minute INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, genre TEXT NOT NULL, source TEXT NOT NULL, ms INTEGER NOT NULL, plays INTEGER NOT NULL, first INTEGER NOT NULL, last INTEGER NOT NULL, PRIMARY KEY(visit,minute))");db.execSQL("CREATE INDEX heard_time ON heard(minute)");db.execSQL("CREATE INDEX heard_uri ON heard(uri)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){if(old<2){db.execSQL("ALTER TABLE heard ADD COLUMN first INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE heard ADD COLUMN last INTEGER NOT NULL DEFAULT 0");}}
    void append(String visit,Track t,long end,long ms,int plays){if(ms<=0)return;io.execute(()->{try{write(visit,t,end,ms,plays);}catch(RuntimeException e){android.util.Log.w("AURA","청취 기록 저장 실패",e);}});}
    void write(String visit,Track t,long end,long ms,int plays){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{long start=end-ms;while(start<end){long minute=Math.floorDiv(start,60000)*60000,stop=Math.min(end,minute+60000);int count=stop==end?plays:0;db.execSQL("INSERT OR IGNORE INTO heard VALUES(?,?,?,?,?,?,?,?,0,0,?,?)",new Object[]{visit,minute,t.uri,t.title,t.artist,t.album,t.genre,t.sourceLabel(),start,stop});db.execSQL("UPDATE heard SET ms=ms+?,plays=plays+?,title=?,artist=?,album=?,genre=?,first=CASE WHEN first=0 THEN ? ELSE MIN(first,?) END,last=MAX(last,?) WHERE visit=? AND minute=?",new Object[]{stop-start,count,t.title,t.artist,t.album,t.genre,start,start,stop,visit,minute});start=stop;}db.setTransactionSuccessful();}finally{db.endTransaction();}}
    record Range(long start,long end,LocalDate date,int period){
        static Range of(int period,LocalDate date,ZoneId zone){LocalDate start=switch(period){case 1->date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));case 2->date.withDayOfMonth(1);default->date;};LocalDate stop=switch(period){case 1->start.plusWeeks(1);case 2->start.plusMonths(1);default->start.plusDays(1);};return new Range(period==3?0:start.atStartOfDay(zone).toInstant().toEpochMilli(),period==3?Long.MAX_VALUE:stop.atStartOfDay(zone).toInstant().toEpochMilli(),start,period);}
    }
    record Rank(String key,String title,String subtitle,String uri,long ms,long plays){}
    record Snapshot(long ms,long plays,int songs,List<Rank> ranks,List<String> genres,long[] bars,String error){}
    Snapshot query(Range range,int category,boolean byPlays,String genre,int limit,ZoneId zone){return query(range,category,byPlays,genre,limit,0,zone);}
    Snapshot query(Range range,int category,boolean byPlays,String genre,int limit,int offset,ZoneId zone){
        SQLiteDatabase db=getReadableDatabase();String where="minute>=? AND minute<?";List<String> args=new ArrayList<>(List.of(""+range.start,""+range.end));
        if(!genre.isEmpty()){where+=" AND genre=?";args.add(genre.equals("__none__")?"":genre);}String[] params=args.toArray(new String[0]);long ms=0,plays=0;int songs=0;
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(ms),0),COALESCE(SUM(plays),0),COUNT(DISTINCT uri) FROM heard WHERE "+where,params)){if(c.moveToFirst()){ms=c.getLong(0);plays=c.getLong(1);songs=c.getInt(2);}}
        String grouping=switch(category){case 1->"artist";case 2->"album,artist";case 3->"genre";default->"uri";};
        List<Rank> ranks=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT uri,title,artist,album,genre,SUM(ms),SUM(plays) FROM heard WHERE "+where+" GROUP BY "+grouping+" ORDER BY "+(byPlays?"SUM(plays) DESC,SUM(ms)":"SUM(ms) DESC,SUM(plays)")+" DESC,title COLLATE NOCASE,"+grouping+" LIMIT "+Math.min(100,Math.max(1,limit))+" OFFSET "+Math.max(0,offset),params)){while(c.moveToNext()){String title=switch(category){case 1->c.getString(2);case 2->c.getString(3);case 3->c.getString(4).isBlank()?"장르 없음":c.getString(4);default->c.getString(1);};String sub=switch(category){case 1->"아티스트";case 2->c.getString(2);case 3->"태그 기준";default->c.getString(2)+" · "+c.getString(3);};ranks.add(new Rank(category==0?c.getString(0):category==3?c.getString(4):title,title,sub,c.getString(0),c.getLong(5),c.getLong(6)));}}
        List<String> genres=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT DISTINCT genre FROM heard WHERE minute>=? AND minute<? ORDER BY genre COLLATE NOCASE",new String[]{""+range.start,""+range.end})){while(c.moveToNext())genres.add(c.getString(0));}
        int bins=range.period==0?24:range.period==1?7:range.period==2?range.date.lengthOfMonth():12;long[] bars=new long[bins];String chartWhere=where;List<String> chartArgs=new ArrayList<>(args);if(range.period==3){LocalDate first=LocalDate.now(zone).withDayOfMonth(1).minusMonths(11);chartWhere+=" AND minute>=?";chartArgs.add(""+first.atStartOfDay(zone).toInstant().toEpochMilli());}
        try(Cursor c=db.rawQuery("SELECT minute,SUM(ms) FROM heard WHERE "+chartWhere+" GROUP BY minute",chartArgs.toArray(new String[0]))){while(c.moveToNext()){ZonedDateTime d=Instant.ofEpochMilli(c.getLong(0)).atZone(zone);int bin=switch(range.period){case 0->d.getHour();case 1->(int)java.time.temporal.ChronoUnit.DAYS.between(range.date,d.toLocalDate());case 2->d.getDayOfMonth()-1;default->(int)java.time.temporal.ChronoUnit.MONTHS.between(LocalDate.now(zone).withDayOfMonth(1).minusMonths(11),d.toLocalDate().withDayOfMonth(1));};if(bin>=0&&bin<bins)bars[bin]+=c.getLong(1);}}
        return new Snapshot(ms,plays,songs,List.copyOf(ranks),List.copyOf(genres),bars,"");
    }
    record Detail(long ms,long plays,int days,long first,long last,long localMs,long driveMs){}
    Detail detail(Range range,int category,Rank rank,String genre,ZoneId zone){
        String where="minute>=? AND minute<?";List<String> args=new ArrayList<>(List.of(""+range.start,""+range.end));
        switch(category){case 0->{where+=" AND uri=?";args.add(rank.key);}case 1->{where+=" AND artist=?";args.add(rank.key);}case 2->{where+=" AND album=? AND artist=?";args.add(rank.key);args.add(rank.subtitle);}case 3->{where+=" AND genre=?";args.add(rank.key);}}
        if(!genre.isEmpty()){where+=" AND genre=?";args.add(genre.equals("__none__")?"":genre);}long ms=0,plays=0,first=0,last=0,local=0,drive=0;Set<LocalDate> days=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT minute,SUM(ms),SUM(plays),MIN(CASE WHEN first=0 THEN minute ELSE first END),MAX(CASE WHEN last=0 THEN minute ELSE last END),SUM(CASE WHEN source='Google Drive' THEN ms ELSE 0 END) FROM heard WHERE "+where+" GROUP BY minute",args.toArray(new String[0]))){while(c.moveToNext()){ms+=c.getLong(1);plays+=c.getLong(2);long f=c.getLong(3),l=c.getLong(4);if(first==0||f<first)first=f;last=Math.max(last,l);drive+=c.getLong(5);local+=c.getLong(1)-c.getLong(5);days.add(Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate());}}
        return new Detail(ms,plays,days.size(),first,last,local,drive);
    }
    void clearAsync(){io.execute(()->getWritableDatabase().delete("heard",null,null));}
}
