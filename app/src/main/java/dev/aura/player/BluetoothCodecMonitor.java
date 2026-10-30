package dev.aura.player;

import android.content.Intent;

/** Displays only codec information reported by the Bluetooth stack; never infers from PCM. */
final class BluetoothCodecMonitor {
    private static volatile String codec;
    private static android.bluetooth.BluetoothA2dp profile;
    private static android.bluetooth.BluetoothAdapter adapter;
    private static boolean requested;

    static synchronized void refresh(android.content.Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            if (profile != null) {
                var devices = profile.getConnectedDevices();
                codec = null;
                if (devices.size() == 1) {
                    Object status =
                            profile.getClass()
                                    .getMethod(
                                            "getCodecStatus",
                                            android.bluetooth.BluetoothDevice.class)
                                    .invoke(profile, devices.get(0));
                    readStatus(status);
                }
                return;
            }
            if (requested) return;
            android.bluetooth.BluetoothManager manager =
                    context.getSystemService(android.bluetooth.BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return;
            requested =
                    adapter.getProfileProxy(
                            context.getApplicationContext(),
                            new android.bluetooth.BluetoothProfile.ServiceListener() {
                                @Override
                                public void onServiceConnected(
                                        int type, android.bluetooth.BluetoothProfile proxy) {
                                    profile = (android.bluetooth.BluetoothA2dp) proxy;
                                    refresh(context.getApplicationContext());
                                }

                                @Override
                                public void onServiceDisconnected(int type) {
                                    profile = null;
                                    codec = null;
                                    requested = false;
                                }
                            },
                            android.bluetooth.BluetoothProfile.A2DP);
        } catch (ReflectiveOperationException | RuntimeException error) {
            codec = null;
            android.util.Log.d(
                    "AuraBluetoothCodec",
                    "Current codec query unavailable: " + (error.getCause() == null ? error : error.getCause()).toString());
        }
    }

    static synchronized void close() {
        if (adapter != null && profile != null)
            adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, profile);
        profile = null;
        adapter = null;
        requested = false;
        codec = null;
    }

    private static void readStatus(Object status) throws ReflectiveOperationException {
        if (status == null) return;
        Object config = status.getClass().getMethod("getCodecConfig").invoke(status);
        if (config == null) return;
        try {
            Object name = config.getClass().getMethod("getCodecName").invoke(config);
            if (name instanceof String value && !value.isBlank()) { codec = value; return; }
        } catch (NoSuchMethodException ignored) {
            // Newer stacks expose the name in the returned configuration instead.
        }
        var match = java.util.regex.Pattern.compile("codecName[:=]([^,}]+)").matcher(config.toString());
        if (match.find()) codec = match.group(1).trim();
    }

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
            readStatus(status);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Some Android versions restrict these system APIs to privileged applications.
        }
    }

    static void associate(android.app.Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(
                android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {android.Manifest.permission.BLUETOOTH_CONNECT}, 12);
            return;
        }
        try {
            if (profile == null || profile.getConnectedDevices().size() != 1) {
                android.widget.Toast.makeText(activity, "블루투스 오디오 기기 한 대를 연결한 뒤 다시 시도해 주세요.", android.widget.Toast.LENGTH_LONG).show();
                refresh(activity);
                return;
            }
            String address = profile.getConnectedDevices().get(0).getAddress();
            var filter = new android.companion.BluetoothDeviceFilter.Builder().setAddress(address).build();
            var request = new android.companion.AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build();
            activity.getSystemService(android.companion.CompanionDeviceManager.class).associate(request,
                    new android.companion.CompanionDeviceManager.Callback() {
                        @Override public void onDeviceFound(android.content.IntentSender chooser) {
                            try { activity.startIntentSenderForResult(chooser, 734, null, 0, 0, 0); }
                            catch (android.content.IntentSender.SendIntentException error) {
                                android.widget.Toast.makeText(activity, "기기 등록 화면을 열 수 없습니다.", android.widget.Toast.LENGTH_LONG).show();
                            }
                        }
                        @Override public void onFailure(CharSequence error) {
                            android.widget.Toast.makeText(activity, "기기 등록을 완료하지 못했습니다. 다시 시도해 주세요.", android.widget.Toast.LENGTH_LONG).show();
                        }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException error) {
            android.widget.Toast.makeText(activity, "이 기기에서는 코덱 조회용 등록을 사용할 수 없습니다.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    static String name() {
        return codec == null ? "" : codec;
    }

    static String description() {
        return codec == null ? "블루투스 코덱: 시스템에서 현재 정보를 제공하지 않습니다." : "블루투스 코덱: " + codec;
    }
}
