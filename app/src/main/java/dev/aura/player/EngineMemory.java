package dev.aura.player;

import android.content.Context;
import android.hardware.usb.UsbManager;

/** USB is temporary: remember the preceding system engine, never pin a disconnected USB route. */
final class EngineMemory {
    static boolean usbPresent(Context c){UsbManager usb=c.getSystemService(UsbManager.class);return usb!=null&&usb.getDeviceList().values().stream().anyMatch(UsbAutoRouting::isAudio);}
    static void enteringUsb(Context c){String current=AudioSettings.current.mode();if(!current.equals("usb"))c.getSharedPreferences("playback",0).edit().putString("engineBeforeUsb",current.equals("hires")?"hires":"android").apply();}
    static String previous(Context c){return c.getSharedPreferences("playback",0).getString("engineBeforeUsb","android").equals("hires")?"hires":"android";}
    static void system(Context c,String mode,boolean explicit){AudioSettings.put("mode",mode.equals("hires")?"hires":"android");AudioSettings.put("perfect",false);AudioSettings.put("device",0);if(AudioSettings.current.dsd().equals("dop"))AudioSettings.put("dsd","pcm");if(explicit)c.getSharedPreferences("playback",0).edit().putBoolean("preferDirectUsb",false).putString("engineBeforeUsb",AudioSettings.current.mode()).apply();}
    static void detached(Context c){if(AudioSettings.current.mode().equals("usb"))system(c,previous(c),false);else if(AudioSettings.current.perfect())AudioSettings.put("perfect",false);}
}
