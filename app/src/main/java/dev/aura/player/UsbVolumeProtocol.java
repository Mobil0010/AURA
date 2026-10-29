package dev.aura.player;

import java.io.IOException;
import java.util.*;

/** UAC Feature Unit requests. Deliberately rejects ambiguous routes and read-only controls. */
final class UsbVolumeProtocol {
    interface Transfer {
        int call(int type, int request, int value, int index, byte[] bytes) throws IOException;
    }

    record Target(int iface, int unit, int protocol, List<Integer> channels) {}

    record Range(int min, int max, int step, int current) {
        int value(int level) {
            long raw = min + (long) (max - min) * Math.max(0, Math.min(100, level)) / 100;
            return Math.max(
                    min, Math.min(max, min + (int) Math.round((raw - min) / (double) step) * step));
        }

        int level() {
            return Math.max(
                    0, Math.min(100, (int) Math.round((current - min) * 100. / (max - min))));
        }
    }

    private record Feature(int iface, int id, int source, int protocol, List<Integer> controls) {}

    static Target find(byte[] raw) throws IOException {
        Map<String, Integer> sources = new HashMap<>();
        List<Feature> features = new ArrayList<>();
        Set<Integer> playback = new HashSet<>(), controlInterfaces = new HashSet<>();
        int iface = -1, sub = 0, protocol = 0, terminal = 0;
        boolean audio = false;
        for (int at = 0; at + 2 <= raw.length; ) {
            int length = raw[at] & 255;
            if (length < 2 || at + length > raw.length)
                throw new IOException("USB 설명자가 올바르지 않습니다.");
            int type = raw[at + 1] & 255;
            if (type == 4 && length >= 9) {
                iface = raw[at + 2] & 255;
                audio = (raw[at + 5] & 255) == 1;
                sub = raw[at + 6] & 255;
                protocol = raw[at + 7] & 255;
                terminal = 0;
                if (audio && sub == 1) controlInterfaces.add(iface);
            } else if (audio && type == 0x24 && length >= 4) {
                int kind = raw[at + 2] & 255, id = raw[at + 3] & 255;
                if (sub == 1 && kind == 6 && (protocol == 0 || protocol == 0x20)) {
                    int start = protocol == 0 ? 6 : 5,
                            size = protocol == 0 && length >= 7 ? raw[at + 5] & 255 : 4;
                    if (size > 0
                            && size <= 4
                            && length >= start + size + 1
                            && (length - start - 1) % size == 0) {
                        List<Integer> controls = new ArrayList<>();
                        for (int p = start; p + size < length; p += size) {
                            int value = 0;
                            for (int b = 0; b < size; b++)
                                value |= (raw[at + p + b] & 255) << (8 * b);
                            controls.add(value);
                        }
                        features.add(new Feature(iface, id, raw[at + 4] & 255, protocol, controls));
                        sources.put(iface + ":" + id, raw[at + 4] & 255);
                    }
                } else if (sub == 1 && kind == 5 && length >= 7 && (raw[at + 4] & 255) == 1)
                    sources.put(iface + ":" + id, raw[at + 5] & 255);
                else if (sub == 2 && kind == 1) terminal = id;
            } else if (audio
                    && sub == 2
                    && type == 5
                    && length >= 7
                    && (raw[at + 2] & 0x80) == 0
                    && (raw[at + 3] & 3) == 1
                    && terminal != 0) playback.add(terminal);
            at += length;
        }
        if (controlInterfaces.size() != 1 || playback.size() != 1)
            throw new IOException("여러 USB 오디오 경로의 볼륨을 자동으로 선택할 수 없습니다.");
        List<Target> matches = new ArrayList<>();
        for (Feature f : features) {
            int source = f.source;
            Set<Integer> seen = new HashSet<>();
            while (sources.containsKey(f.iface + ":" + source) && seen.add(source))
                source = sources.get(f.iface + ":" + source);
            if (!playback.contains(source)) continue;
            List<Integer> channels = new ArrayList<>();
            if (writable(f.controls.get(0), f.protocol)) channels.add(0);
            else {
                if (f.controls.size() < 2) continue;
                for (int i = 1; i < f.controls.size(); i++)
                    if (writable(f.controls.get(i), f.protocol)) channels.add(i);
                if (channels.size() != f.controls.size() - 1) continue;
            }
            matches.add(new Target(f.iface, f.id, f.protocol, List.copyOf(channels)));
        }
        if (matches.size() != 1) throw new IOException("이 DAC는 지원되는 하드웨어 볼륨을 제공하지 않습니다.");
        return matches.get(0);
    }

    private static boolean writable(int bits, int protocol) {
        return protocol == 0x20 ? ((bits >> 2) & 3) == 3 : (bits & 2) != 0;
    }

    static int signed(byte[] bytes, int at) {
        return (short) ((bytes[at] & 255) | (bytes[at + 1] & 255) << 8);
    }

    private static int get(Transfer transfer, Target target, int channel, int request)
            throws IOException {
        byte[] bytes = new byte[2];
        if (transfer.call(0xa1, request, 0x200 | channel, target.unit << 8 | target.iface, bytes)
                != 2) throw new IOException("DAC 볼륨 정보를 읽지 못했습니다.");
        return signed(bytes, 0);
    }

    static Range read(Transfer transfer, Target target) throws IOException {
        Range common = null;
        for (int channel : target.channels) {
            int current = get(transfer, target, channel, target.protocol == 0x20 ? 1 : 0x81),
                    min,
                    max,
                    step;
            if (target.protocol == 0x20) {
                byte[] bytes = new byte[8];
                if (transfer.call(0xa1, 2, 0x200 | channel, target.unit << 8 | target.iface, bytes)
                                != 8
                        || signed(bytes, 0) != 1) throw new IOException("지원하지 않는 DAC 볼륨 범위입니다.");
                min = signed(bytes, 2);
                max = signed(bytes, 4);
                step = signed(bytes, 6);
            } else {
                min = get(transfer, target, channel, 0x82);
                max = get(transfer, target, channel, 0x83);
                step = get(transfer, target, channel, 0x84);
            }
            if (min >= max || step < 0 || current < min || current > max)
                throw new IOException("DAC 볼륨 범위가 올바르지 않습니다.");
            Range range = new Range(min, max, Math.max(1, step), current);
            if (common != null
                    && (common.min != range.min
                            || common.max != range.max
                            || common.step != range.step
                            || common.current != range.current))
                throw new IOException("좌우 볼륨이 다른 DAC는 기기 볼륨으로 조절해 주세요.");
            common = range;
        }
        if (common == null) throw new IOException("DAC 볼륨 채널이 없습니다.");
        return common;
    }

    static Range write(Transfer transfer, Target target, Range range, int level)
            throws IOException {
        return writeValue(transfer, target, range, range.value(level));
    }

    static Range writeValue(Transfer transfer, Target target, Range range, int requested)
            throws IOException {
        int clamped = Math.max(range.min, Math.min(range.max, requested));
        int steps = (int) Math.round((clamped - range.min) / (double) range.step);
        int stepped = range.min + steps * range.step;
        int value = Math.max(range.min, Math.min(range.max, stepped));
        for (int channel : target.channels) {
            byte[] bytes = {(byte) value, (byte) (value >> 8)};
            if (transfer.call(0x21, 1, 0x200 | channel, target.unit << 8 | target.iface, bytes)
                    != 2) throw new IOException("DAC 볼륨을 변경하지 못했습니다.");
        }
        Range verified = read(transfer, target);
        if (Math.abs(verified.current - value) > range.step)
            throw new IOException("DAC 볼륨 변경을 확인하지 못했습니다.");
        return verified;
    }
}
