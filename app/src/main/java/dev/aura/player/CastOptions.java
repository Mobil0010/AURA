package dev.aura.player;
import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.*;
import java.util.List;
public final class CastOptions implements OptionsProvider {
    @Override public com.google.android.gms.cast.framework.CastOptions getCastOptions(Context context){return new com.google.android.gms.cast.framework.CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build();}
    @Override public List<SessionProvider> getAdditionalSessionProviders(Context context){return null;}
}
