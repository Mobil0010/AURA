package dev.aura.player;

import android.content.Context;
import android.media.*;
import android.os.Build;
import java.util.*;

/** Preflight configuration changes before interrupting the currently playing stream. */
final class OutputRouting {
    record Device(int id, boolean usb, boolean bitPerfect) {}

    record Capabilities(int sdk, boolean usbOpen, List<Device> devices) {}

    record Decision(boolean allowed, int deviceId, String message) {}

    static boolean usb(AudioDeviceInfo d) {
        return d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                || d.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static Capabilities capabilities(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<Device> devices = new ArrayList<>();
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            boolean capable = false;
            if (Build.VERSION.SDK_INT >= 34 && usb(d))
                try {
                    for (AudioMixerAttributes a : manager.getSupportedMixerAttributes(d))
                        if (a.getMixerBehavior()
                                == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                            capable = true;
                            break;
                        }
                } catch (IllegalArgumentException | SecurityException ignored) {
                }
            devices.add(new Device(d.getId(), usb(d), capable));
        }
        return new Capabilities(Build.VERSION.SDK_INT, UsbRoute.available(context), devices);
    }

    static Decision assess(Capabilities cap, String mode, boolean perfect, int selected) {
        if (mode.equals("usb"))
            return cap.usbOpen()
                    ? new Decision(true, selected, "")
                    : new Decision(
                            false,
                            selected,
                            "USB DAC 연결 권한이 필요합니다. 먼저 USB DAC를 연결하고 기기 선택과 권한 허용을 완료해 주세요.");
        if (!perfect) return new Decision(true, selected, "");
        if (cap.sdk() < 34)
            return new Decision(
                    false,
                    selected,
                    "Android 공식 비트퍼펙트는 Android 14 이상에서 제공됩니다. USB DAC 직접 출력은 Android 버전과 별개의 경로로 사용할 수 있습니다.");
        List<Device> eligible =
                cap.devices().stream()
                        .filter(d -> d.usb() && d.bitPerfect())
                        .collect(java.util.stream.Collectors.toList());
        for (Device d : eligible) if (d.id() == selected) return new Decision(true, d.id(), "");
        if (eligible.size() == 1) return new Decision(true, eligible.get(0).id(), "");
        if (eligible.size() > 1)
            return new Decision(
                    false,
                    selected,
                    "비트퍼펙트를 지원하는 USB 출력이 여러 개 연결되어 있습니다. 출력 기기에서 사용할 USB DAC를 선택해 주세요.");
        return new Decision(
                false,
                selected,
                "연결된 USB 출력에서 Android 공식 비트퍼펙트 지원 정보를 찾지 못했습니다. USB DAC가 연결되어 있다면 USB 직접 출력으로 연결할 수 있습니다.");
    }

    static Decision assess(Context c, String mode, boolean perfect, int device) {
        return assess(capabilities(c), mode, perfect, device);
    }

    static Decision current(Context c) {
        var cfg = AudioSettings.current;
        return assess(c, cfg.mode(), cfg.perfect(), cfg.deviceId());
    }

    static void requireReady(Context context) {
        Decision result = current(context);
        if (!result.allowed()) throw new IllegalStateException(result.message());
        if (result.deviceId() != AudioSettings.current.deviceId())
            AudioSettings.put("device", result.deviceId());
    }
}
