package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import java.io.*;
import java.util.*;

public class UsbVolumeProtocolTest {
    private byte[] descriptors(boolean uac2, int master) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b : new int[] {9, 4, 0, 0, 0, 1, 1, uac2 ? 32 : 0, 0}) out.write(b);
        if (uac2) {
            for (int b : new int[] {18, 36, 6, 2, 1, master, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})
                out.write(b);
        } else for (int b : new int[] {10, 36, 6, 2, 1, 1, master, 0, 0, 0}) out.write(b);
        for (int b :
                new int[] {
                    9,
                    4,
                    1,
                    1,
                    1,
                    1,
                    2,
                    uac2 ? 32 : 0,
                    0,
                    7,
                    36,
                    1,
                    1,
                    1,
                    1,
                    0,
                    7,
                    5,
                    1,
                    1,
                    192,
                    0,
                    1
                }) out.write(b);
        return out.toByteArray();
    }

    @Test
    public void recognizesUac1AndUac2WritableMasterOnly() throws Exception {
        for (boolean uac2 : new boolean[] {false, true}) {
            var target = UsbVolumeProtocol.find(descriptors(uac2, uac2 ? 12 : 2));
            assertEquals(2, target.unit());
            assertEquals(0, target.iface());
            assertEquals(List.of(0), target.channels());
            assertEquals(uac2 ? 32 : 0, target.protocol());
        }
    }

    @Test
    public void readOnlyAndBrokenDescriptorsAreRejected() throws Exception {
        byte[] single = descriptors(false, 2), multi = Arrays.copyOf(single, single.length + 9);
        byte[] another = {9, 4, 2, 0, 0, 1, 1, 0, 0};
        System.arraycopy(another, 0, multi, single.length, 9);
        for (byte[] raw :
                new byte[][] {
                    multi,
                    descriptors(true, 4),
                    descriptors(false, 0),
                    new byte[] {0, 4},
                    new byte[] {18, 36, 6}
                })
            try {
                UsbVolumeProtocol.find(raw);
                fail("Unsupported volume accepted");
            } catch (IOException expected) {
            }
    }

    @Test
    public void uacRequestsClampQuantizeAndVerifyHardwareReadback() throws Exception {
        for (boolean uac2 : new boolean[] {false, true}) {
            var target = UsbVolumeProtocol.find(descriptors(uac2, uac2 ? 12 : 2));
            int[] current = {-20 * 256}, writes = {0};
            UsbVolumeProtocol.Transfer transfer =
                    (type, request, value, index, bytes) -> {
                        assertEquals(0x200, value);
                        assertEquals(0x200, index);
                        if (type == 0x21) {
                            assertEquals(1, request);
                            current[0] = UsbVolumeProtocol.signed(bytes, 0);
                            writes[0]++;
                            return 2;
                        }
                        if (uac2 && request == 2) {
                            put(bytes, 0, 1);
                            put(bytes, 2, -60 * 256);
                            put(bytes, 4, 0);
                            put(bytes, 6, 256);
                            return 8;
                        }
                        int v =
                                request == 0x82
                                        ? -60 * 256
                                        : request == 0x83 ? 0 : request == 0x84 ? 256 : current[0];
                        put(bytes, 0, v);
                        return 2;
                    };
            var range = UsbVolumeProtocol.read(transfer, target);
            assertEquals(-20 * 256, range.current());
            var result = UsbVolumeProtocol.write(transfer, target, range, 50);
            assertEquals(-30 * 256, result.current());
            assertEquals(1, writes[0]);
            assertEquals(0, range.value(200));
            assertEquals(-60 * 256, range.value(-20));
        }
    }

    @Test
    public void stalledOrUnverifiedWritesAreNotReportedAsSuccess() throws Exception {
        var target = UsbVolumeProtocol.find(descriptors(false, 2));
        var range = new UsbVolumeProtocol.Range(-60 * 256, 0, 256, -20 * 256);
        try {
            UsbVolumeProtocol.write((t, r, v, i, b) -> -1, target, range, 50);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(1, UsbVolume.gain(100), 0);
        assertEquals(0, UsbVolume.gain(0), 0);
        assertTrue(UsbVolume.gain(50) < .04);
    }

    private static void put(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >> 8);
    }
}
