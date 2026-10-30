package dev.aura.player;

import android.content.*;
import android.hardware.usb.*;
import android.media.*;
import android.os.*;

/** Route policy lives with playback, so it also works with the Activity closed. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class UsbAutoRouting implements AutoCloseable {
    private final PlaybackService service;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences prefs;
    private final java.util.Set<Integer> knownUsb = new java.util.HashSet<>();
    private final java.util.Set<Integer> knownOutputs = new java.util.HashSet<>();
    private boolean usbActive;
    private int routeId, directId = -1;
    private long detachedAt;
    private final AudioDeviceCallback callback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                    for (var d : devices)
                        if (knownOutputs.add(d.getId()) && !OutputRouting.usb(d)) added(d);
                    for (var d : devices)
                        if (OutputRouting.usb(d)) {
                            if (knownUsb.add(d.getId())
                                    && !(AudioSettings.current.mode().equals("usb")
                                            && UsbRoute.available(service))) pauseForAttach();
                            main.removeCallbacks(reconcile);
                            main.postDelayed(reconcile, 400);
                            break;
                        }
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                    for (var d : devices) {
                        knownOutputs.remove(d.getId());
                        if (!OutputRouting.usb(d) && d.getId() == routeId) {
                            service.pauseForDisconnect();
                            routeId = 0;
                            service.automaticRoute(0, true);
                        }
                    }
                    for (var d : devices)
                        if (OutputRouting.usb(d)) {
                            knownUsb.remove(d.getId());
                            if (AudioSettings.current.mode().equals("usb")
                                    && UsbRoute.available(service)) return;
                            if (d.getId() == routeId
                                    || d.getId() == AudioSettings.current.deviceId()
                                    || AudioSettings.current.mode().equals("usb")) {
                                usbActive = true;
                                detach();
                            }
                            break;
                        }
                }
            };
    private final Runnable reconcile = this::attach;
    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String action = i.getAction();
                    BluetoothCodecMonitor.receive(i);
                    if (action != null && action.startsWith("android.bluetooth."))
                        BluetoothCodecMonitor.refresh(service);
                    if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (d != null
                                && isAudio(d)
                                && ((AudioSettings.current.mode().equals("usb")
                                                && (d.getDeviceId() == directId
                                                        || d.getDeviceId()
                                                                == UsbRoute.selectedId()))
                                        || (!AudioSettings.current.mode().equals("usb")
                                                && usbActive
                                                && service
                                                        .getSystemService(UsbManager.class)
                                                        .getDeviceList()
                                                        .values()
                                                        .stream()
                                                        .noneMatch(UsbAutoRouting::isAudio))))
                            detach();
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                        UsbDevice attached = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (attached == null || !isAudio(attached)) return;
                        pauseForAttach();
                        main.removeCallbacks(reconcile);
                        main.postDelayed(reconcile, 700);
                    } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)
                            && SystemClock.elapsedRealtime() - detachedAt > 1500) {
                        if (!usbActive || prefs.getBoolean("pauseUsbDetach", true))
                            service.pauseForDisconnect();
                    }
                }
            };

    UsbAutoRouting(PlaybackService s) {
        service = s;
        BluetoothCodecMonitor.refresh(s);
        audio = s.getSystemService(AudioManager.class);
        prefs = s.getSharedPreferences("playback", 0);
        usbActive = AudioSettings.current.mode().equals("usb");
        routeId = AudioSettings.current.deviceId();
        directId = UsbRoute.selectedId();
        for (var d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            knownOutputs.add(d.getId());
            if (OutputRouting.usb(d)) knownUsb.add(d.getId());
        }
        audio.registerAudioDeviceCallback(callback, main);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        androidx.core.content.ContextCompat.registerReceiver(
                s, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        main.postDelayed(
                () -> {
                    boolean present = false;
                    for (var d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                        if (OutputRouting.usb(d)) present = true;
                    if (!present
                            && service
                                    .getSystemService(UsbManager.class)
                                    .getDeviceList()
                                    .values()
                                    .stream()
                                    .noneMatch(UsbAutoRouting::isAudio)
                            && (AudioSettings.current.mode().equals("usb")
                                    || AudioSettings.current.perfect())) {
                        usbActive = true;
                        detach();
                    } else restore();
                },
                350);
    }

    static boolean isAudio(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++)
            if (d.getInterface(i).getInterfaceClass() == 1) return true;
        return false;
    }

    void actualSelected(AudioDeviceInfo d) {
        if (!AudioSettings.current.mode().equals("usb")) {
            usbActive = OutputRouting.usb(d);
            routeId = d.getId();
        }
    }

    private String identity(AudioDeviceInfo device) {
        String address = Build.VERSION.SDK_INT >= 28 ? device.getAddress() : "";
        return device.getType() + ":" + address + ":" + device.getProductName();
    }

    private void restore() {
        String saved = prefs.getString("lastOutputIdentity", "");
        AudioDeviceInfo best = null;
        int bestRank = -1;
        for (var device :
                OutputDevices.music(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS), routeId)) {
            if (identity(device).equals(saved)) {
                best = device;
                break;
            }
            int type = device.getType();
            int rank =
                    OutputRouting.usb(device)
                            ? 4
                            : type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                    ? 3
                                    : OutputDevices.bluetooth(type)
                                            ? 2
                                            : type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? 1 : 0;
            if (rank > bestRank) {
                bestRank = rank;
                best = device;
            }
        }
        if (best == null) return;
        if (OutputRouting.usb(best)) {
            attach();
            return;
        }
        if (AudioSettings.current.mode().equals("usb")) EngineMemory.detached(service);
        AudioSettings.put("perfect", false);
        routeId = best.getId();
        service.automaticRoute(routeId, false);
    }

    void systemSelected(AudioDeviceInfo device, boolean perfect) {
        prefs.edit().putString("lastOutputIdentity", identity(device)).apply();
        usbActive = OutputRouting.usb(device);
        routeId = device.getId();
        prefs.edit()
                .putBoolean("preferDirectUsb", false)
                .putBoolean("preferSystemPerfect", usbActive && perfect)
                .apply();
    }

    void directSelected() {
        usbActive = true;
        routeId = 0;
        directId = UsbRoute.selectedId();
    }

    private void added(AudioDeviceInfo device) {
        int type = device.getType();
        boolean wired =
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
        boolean bluetooth =
                OutputDevices.bluetooth(type) && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        if ((!wired && !bluetooth) || !prefs.getBoolean("autoNewOutput", true)) return;
        AudioDeviceInfo current = null;
        for (var port : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (port.getId() == routeId) current = port;
        if (!RoutePolicy.selectNew(
                true,
                wired,
                bluetooth,
                AudioSettings.current.mode().equals("usb")
                        || current != null
                                && current.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            return;
        if (AudioSettings.current.mode().equals("usb")) {
            UsbRoute.close();
            EngineMemory.detached(service);
        }
        AudioSettings.put("perfect", false);
        if (AudioSettings.current.dsd().equals("dop")) AudioSettings.put("dsd", "pcm");
        usbActive = false;
        routeId = device.getId();
        prefs.edit().putString("lastOutputIdentity", identity(device)).apply();
        service.automaticRoute(routeId, false);
    }

    private void attach() {
        if (!prefs.getBoolean("autoUsb", true)) return;
        AudioDeviceInfo selected = null;
        for (var d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (OutputRouting.usb(d)) {
                selected = d;
                break;
            }
        if (AudioSettings.current.mode().equals("usb") && UsbRoute.available(service)) {
            usbActive = true;
            return;
        }
        UsbManager manager = service.getSystemService(UsbManager.class);
        if (AudioSettings.current.mode().equals("usb")) {
            for (UsbDevice d : manager.getDeviceList().values())
                if (isAudio(d) && manager.hasPermission(d)) {
                    try {
                        service.switchUsb(d, AudioSettings.current.perfect());
                        usbActive = true;
                        return;
                    } catch (RuntimeException e) {
                        PcmSink.lastFailure = e.getMessage();
                        break;
                    }
                }
            PcmSink.status = "선택한 USB 엔진 유지 · DAC 연결·권한 대기";
            return;
        }
        if (prefs.getBoolean("preferDirectUsb", false)) {
            for (UsbDevice device : manager.getDeviceList().values())
                if (isAudio(device) && manager.hasPermission(device)) {
                    try {
                        service.switchUsb(device, prefs.getBoolean("preferPerfectUsb", false));
                        return;
                    } catch (RuntimeException error) {
                        PcmSink.lastFailure = error.getMessage();
                        break;
                    }
                }
        }
        if (selected == null) return;
        if (usbActive && routeId == selected.getId() && AudioSettings.current.deviceId() == routeId)
            return;
        usbActive = true;
        routeId = selected.getId();
        service.automaticRoute(routeId, false);
    }

    void pauseForAttach() {
        if (prefs.getBoolean("pauseUsbAttach", false)) service.pauseForDisconnect();
    }

    private void detach() {
        if (!usbActive && !AudioSettings.current.mode().equals("usb")) return;
        if (!AudioSettings.current.mode().equals("usb"))
            prefs.edit().putBoolean("preferSystemPerfect", AudioSettings.current.perfect()).apply();
        usbActive = false;
        routeId = 0;
        directId = -1;
        detachedAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(reconcile);
        int phone = 0;
        for (var d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                phone = d.getId();
                break;
            }
        EngineMemory.detached(service);
        UsbRoute.close();
        service.automaticRoute(phone, true);
    }

    @Override
    public void close() {
        BluetoothCodecMonitor.close();
        main.removeCallbacksAndMessages(null);
        audio.unregisterAudioDeviceCallback(callback);
        service.unregisterReceiver(receiver);
    }
}
