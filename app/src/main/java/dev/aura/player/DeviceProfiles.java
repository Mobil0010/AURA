package dev.aura.player;

import android.content.*;
import android.media.*;
import android.os.Build;
import org.json.*;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class DeviceProfiles {
    static final Set<String> KEYS =
            Set.of(
                    "rate",
                    "bits",
                    "buffer",
                    "sox",
                    "quality",
                    "phase",
                    "cutoff",
                    "dither",
                    "eq",
                    "bands",
                    "graphicGains",
                    "preamp",
                    "crossfeed",
                    "balance",
                    "limiter",
                    "autoEqPreamp",
                    "eqMode",
                    "dynamicBass",
                    "bassFrequency",
                    "replayGain",
                    "volumeCeiling");
    static String last = "";
    static AudioDeviceInfo routed;

    static String key(Context c, AudioDeviceInfo route) {
        if (AudioSettings.current.mode().equals("usb")) return "usb:" + UsbRoute.identity;
        if (route != null)
            return "output:"
                    + route.getType()
                    + ":"
                    + (Build.VERSION.SDK_INT >= 28 && !route.getAddress().isBlank()
                            ? route.getAddress()
                            : route.getProductName());
        AudioManager a = c.getSystemService(AudioManager.class);
        if (routed != null)
            for (var d : a.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getId() == routed.getId()) return key(c, d);
        for (var d : a.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getId() == AudioSettings.current.deviceId()) return key(c, d);
        return "output:2:speaker";
    }

    static void save(Context c) {
        try {
            JSONObject j = new JSONObject();
            for (var e : c.getSharedPreferences("audio", 0).getAll().entrySet())
                if (KEYS.contains(e.getKey())) j.put(e.getKey(), e.getValue());
            MusicCollections.prefs(c)
                    .edit()
                    .putString("soundProfile:" + key(c, null), j.toString())
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean apply(Context c, AudioDeviceInfo route, boolean force) {
        if (route != null) routed = route;
        String key = key(c, route);
        if (!force
                && (key.equals(last)
                        || !MusicCollections.prefs(c).getBoolean("autoProfiles", false)))
            return false;
        last = key;
        String saved = MusicCollections.prefs(c).getString("soundProfile:" + key, "");
        if (saved.isEmpty()) return false;
        try {
            JSONObject j = new JSONObject(saved);
            for (var i = j.keys(); i.hasNext(); ) {
                String k = i.next();
                if (KEYS.contains(k)) AudioSettings.put(k, j.get(k));
            }
            ReplayGain.update();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
