package dev.aura.player;

import androidx.media3.common.*;
import androidx.media3.common.FlagSet;
import java.lang.reflect.*;
import java.util.*;

/** MediaSession's remote-volume domain routes hardware keys here, including in background playback. */
@androidx.media3.common.util.UnstableApi
final class UsbVolumePlayer extends ForwardingPlayer {
    private final Map<Player.Listener, Player.Listener> listeners = new IdentityHashMap<>();
    private static final DeviceInfo USB =
            new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(100)
                    .build();
    private DeviceInfo lastDevice;
    private int lastVolume = -1;
    private Player.Commands lastCommands;
    private int unmuted = 100;

    UsbVolumePlayer(Player player) {
        super(player);
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return UsbVolume.direct() ? USB : super.getDeviceInfo();
    }

    @Override
    public int getDeviceVolume() {
        return UsbVolume.direct() ? UsbVolume.state.level() : super.getDeviceVolume();
    }

    @Override
    public boolean isDeviceMuted() {
        return UsbVolume.direct()
                ? !UsbVolume.state.hardware() && UsbVolume.state.level() == 0
                : super.isDeviceMuted();
    }

    @Override
    public Commands getAvailableCommands() {
        var commands = super.getAvailableCommands().buildUpon();
        if (UsbVolume.direct())
            commands.addAll(
                    COMMAND_GET_DEVICE_VOLUME,
                    COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS);
        return commands.build();
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return getAvailableCommands().contains(command);
    }

    @Override
    public void setDeviceVolume(int value, int flags) {
        if (UsbVolume.direct()) UsbVolume.set(value);
        else super.setDeviceVolume(value, flags);
    }

    @Override
    public void increaseDeviceVolume(int flags) {
        if (UsbVolume.direct()) UsbVolume.adjust(2);
        else super.increaseDeviceVolume(flags);
    }

    @Override
    public void decreaseDeviceVolume(int flags) {
        if (UsbVolume.direct()) UsbVolume.adjust(-2);
        else super.decreaseDeviceVolume(flags);
    }

    @Override
    public void setDeviceMuted(boolean muted, int flags) {
        if (!UsbVolume.direct()) {
            super.setDeviceMuted(muted, flags);
            return;
        }
        if (muted) {
            unmuted = Math.max(2, UsbVolume.state.level());
            UsbVolume.set(0);
        } else UsbVolume.set(unmuted);
    }

    @Override
    public void setDeviceVolume(int value) {
        setDeviceVolume(value, 0);
    }

    @Override
    public void increaseDeviceVolume() {
        increaseDeviceVolume(0);
    }

    @Override
    public void decreaseDeviceVolume() {
        decreaseDeviceVolume(0);
    }

    @Override
    public void setDeviceMuted(boolean muted) {
        setDeviceMuted(muted, 0);
    }

    @Override
    public void addListener(Player.Listener listener) {
        if (listeners.containsKey(listener)) return;
        Player.Listener forwarding =
                (Player.Listener)
                        Proxy.newProxyInstance(
                                Player.Listener.class.getClassLoader(),
                                new Class<?>[] {Player.Listener.class},
                                (proxy, method, args) -> {
                                    if (method.getDeclaringClass() == Object.class) {
                                        return switch (method.getName()) {
                                            case "equals" -> proxy == args[0];
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            default -> "AURA volume listener";
                                        };
                                    }
                                    if (method.getName().equals("onDeviceInfoChanged"))
                                        args = new Object[] {getDeviceInfo()};
                                    else if (method.getName().equals("onDeviceVolumeChanged"))
                                        args = new Object[] {getDeviceVolume(), isDeviceMuted()};
                                    else if (method.getName().equals("onAvailableCommandsChanged"))
                                        args = new Object[] {getAvailableCommands()};
                                    try {
                                        return method.invoke(listener, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        listeners.put(listener, forwarding);
        super.addListener(forwarding);
    }

    @Override
    public void removeListener(Player.Listener listener) {
        Player.Listener forwarding = listeners.remove(listener);
        if (forwarding != null) super.removeListener(forwarding);
    }

    void signal() {
        DeviceInfo device = getDeviceInfo();
        int volume = getDeviceVolume();
        Player.Commands commands = getAvailableCommands();
        boolean deviceChanged = !device.equals(lastDevice),
                volumeChanged = volume != lastVolume,
                commandsChanged = !commands.equals(lastCommands);
        lastDevice = device;
        lastVolume = volume;
        lastCommands = commands;
        if (!deviceChanged && !volumeChanged && !commandsChanged) return;
        FlagSet.Builder flags = new FlagSet.Builder();
        if (deviceChanged) flags.add(EVENT_DEVICE_INFO_CHANGED);
        if (volumeChanged) flags.add(EVENT_DEVICE_VOLUME_CHANGED);
        if (commandsChanged) flags.add(EVENT_AVAILABLE_COMMANDS_CHANGED);
        Player.Events events = new Player.Events(flags.build());
        for (Player.Listener listener : new ArrayList<>(listeners.keySet())) {
            if (deviceChanged) listener.onDeviceInfoChanged(device);
            if (volumeChanged) listener.onDeviceVolumeChanged(volume, isDeviceMuted());
            if (commandsChanged) listener.onAvailableCommandsChanged(commands);
            listener.onEvents(this, events);
        }
    }
}
