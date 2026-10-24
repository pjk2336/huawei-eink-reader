package org.nfcresearch.qpolllab;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

public final class LzoCompressionTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        byte[][] patterns = new byte[5][];
        patterns[0] = new byte[2000];
        patterns[1] = new byte[2000];
        Arrays.fill(patterns[1], (byte) 0x55);
        patterns[2] = new byte[2000];
        new Random(9273).nextBytes(patterns[2]);
        patterns[3] = "墨读白底黑字白底黑字白底黑字白底黑字".getBytes("UTF-8");
        byte[] repeated = new byte[2000];
        for (int i = 0; i < repeated.length; i++) repeated[i] = patterns[3][i % patterns[3].length];
        patterns[3] = repeated;
        patterns[4] = new byte[997];
        Arrays.fill(patterns[4], (byte) 0xAA);

        Path output = Paths.get(args[0]);
        try (DataOutputStream fixture = new DataOutputStream(
                new FileOutputStream(output.resolve("lzo-compressed-fixtures.bin").toFile()))) {
            for (byte[] raw : patterns) {
                write(fixture, raw);
            }
            Random random = new Random(220718);
            for (int length = 1; length <= 2000; length++) {
                byte[] raw = new byte[length];
                if (length % 3 == 0) {
                    random.nextBytes(raw);
                } else if (length % 3 == 1) {
                    Arrays.fill(raw, (byte) (length & 255));
                } else {
                    for (int i = 0; i < raw.length; i++) {
                        raw[i] = (byte) (i % 17 < 4 ? (i * 31) : 0x55);
                    }
                }
                write(fixture, raw);
            }
        }
        check(PanelProtocol.compressedLzo(patterns[0]).length < 30, "zero block compression");
        check(PanelProtocol.compressedLzo(patterns[1]).length < 30, "white block compression");
        System.out.println("LzoCompressionTest: PASS");
    }

    private static void write(DataOutputStream fixture, byte[] raw) throws Exception {
        byte[] compressed = PanelProtocol.compressedLzo(raw);
        check(compressed.length > 0, "empty compressed result");
        check(compressed.length <= PanelProtocol.literalLzo(raw).length,
                "compressed output larger than literal");
        fixture.writeInt(raw.length);
        fixture.writeInt(compressed.length);
        fixture.write(compressed);
        fixture.write(raw);
    }
}
