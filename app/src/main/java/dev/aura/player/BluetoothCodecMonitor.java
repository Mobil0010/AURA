package dev.aura.player;

import android.content.Intent;

/** Displays only codec information reported by the Bluetooth stack; never infers from PCM. */
final class BluetoothCodecMonitor {
    private static volatile String codec;

    static void receive(Intent intent) {
        if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
                .equals(intent.getAction())) {
            codec = null;
            return;
        }
        if (!"android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
                .equals(intent.getAction())) return;
        codec = null;
        try {
            Object status = intent.getParcelableExtra("android.bluetooth.extra.CODEC_STATUS");
            if (status == null) return;
            Object config = status.getClass().getMethod("getCodecConfig").invoke(status);
            Object name = config.getClass().getMethod("getCodecName").invoke(config);
            if (name instanceof String value && !value.isBlank()) codec = value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Some Android versions restrict these system APIs to privileged applications.
        }
    }

    static String description() {
        return codec == null ? "블루투스 코덱: 시스템에서 현재 정보를 제공하지 않습니다." : "블루투스 코덱: " + codec;
    }
}
