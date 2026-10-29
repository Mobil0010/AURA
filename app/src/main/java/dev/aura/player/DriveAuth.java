package dev.aura.player;

import android.accounts.Account;
import android.app.Activity;
import android.content.*;
import android.os.SystemClock;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Google manages consent and refresh; access tokens are held in memory only. */
final class DriveAuth implements DriveHttp.Tokens {
    static final String SCOPE="https://www.googleapis.com/auth/drive.readonly";
    private static DriveAuth instance;private final Context context;private final SharedPreferences prefs;
    private final Object refresh=new Object();private String access="";private long acquired;private volatile long generation;
    static synchronized DriveAuth get(Context c){if(instance==null)instance=new DriveAuth(c.getApplicationContext());return instance;}
    private DriveAuth(Context c){context=c;prefs=c.getSharedPreferences("drive",0);}
    boolean connected(){return !accountId().isEmpty();}String accountId(){return prefs.getString("accountId","");}String email(){return prefs.getString("email","");}String display(){return prefs.getString("display",email());}
    AuthorizationRequest request(){var b=AuthorizationRequest.builder().setRequestedScopes(List.of(new Scope(SCOPE)));if(connected()&&!email().isEmpty())b.setAccount(new Account(email(),"com.google"));return b.build();}
    void begin(Activity activity,java.util.function.Consumer<AuthorizationResult> success,java.util.function.Consumer<Exception> error){try{Identity.getAuthorizationClient(activity).authorize(request()).addOnSuccessListener(activity,success::accept).addOnFailureListener(activity,error::accept);}catch(Exception e){error.accept(e);}}
    synchronized void accept(String id,String email,String display,String token)throws IOException{
        DriveUris.id(id);if(token==null||token.isBlank())throw new IOException("Google Drive 읽기 권한을 허용해 주세요.");
        synchronized(DriveLibrary.LOCK){if(!id.equals(accountId()))DriveLibrary.clear(context);prefs.edit().putString("accountId",id).putString("email",email).putString("display",display).apply();generation++;access=token;acquired=SystemClock.elapsedRealtime();}
    }
    long generation(){return generation;}
    @Override public String token()throws IOException{synchronized(refresh){long expected;AuthorizationRequest request;synchronized(this){if(!connected())throw new IOException("Google Drive 계정을 먼저 연결해 주세요.");if(!access.isEmpty()&&SystemClock.elapsedRealtime()-acquired<40*60*1000)return access;expected=generation;request=request();}
        try{var result=Tasks.await(Identity.getAuthorizationClient(context).authorize(request),20,TimeUnit.SECONDS);if(result.hasResolution()||result.getAccessToken()==null)throw new IOException("Google Drive를 다시 연결해 권한을 확인해 주세요.");synchronized(this){if(expected!=generation||!connected())throw new IOException("Google Drive 연결이 변경되었습니다.");access=result.getAccessToken();acquired=SystemClock.elapsedRealtime();return access;}}catch(IOException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("Google Drive 연결이 만료되었습니다. 다시 연결해 주세요.");}
    }}
    @Override public void invalidate(String old){synchronized(this){if(old.equals(access)){access="";acquired=0;}}try{Tasks.await(Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(old).build()),10,TimeUnit.SECONDS);}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}}
    void disconnect(java.util.function.Consumer<Boolean> result){String email=email();if(!connected()){result.accept(true);return;}var request=RevokeAccessRequest.builder().setAccount(new Account(email,"com.google")).setScopes(List.of(new Scope(SCOPE))).build();clear();try{Identity.getAuthorizationClient(context).revokeAccess(request).addOnCompleteListener(task->result.accept(task.isSuccessful()));}catch(RuntimeException e){result.accept(false);}}
    synchronized void clear(){synchronized(DriveLibrary.LOCK){generation++;access="";acquired=0;DriveLibrary.clear(context);prefs.edit().clear().apply();}}
}
