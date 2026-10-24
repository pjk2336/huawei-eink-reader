package org.nfcresearch.qpolllab;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Optional diagnostic: count literal versus compressed D3 packets for a packed frame. */
public final class PacketCountProbe {
    public static void main(String[] args) throws Exception {
        byte[] packed = Files.readAllBytes(Paths.get(args[0]));
        if (packed.length != PanelProtocol.IMAGE_BYTES) throw new IllegalArgumentException("bad frame");
        int literalPackets = 0;
        int compressedPackets = 0;
        int compressedBytes = 0;
        for (int offset = 0; offset < packed.length; offset += 2000) {
            byte[] raw = java.util.Arrays.copyOfRange(packed, offset,
                    Math.min(offset + 2000, packed.length));
            literalPackets += (PanelProtocol.literalLzo(raw).length + 249) / 250;
            byte[] compressed = PanelProtocol.compressedLzo(raw);
            compressedBytes += compressed.length;
            compressedPackets += (compressed.length + 249) / 250;
        }
        System.out.println("literalPackets=" + literalPackets
                + " compressedPackets=" + compressedPackets
                + " compressedBytes=" + compressedBytes
                + " reduction=" + (100 - compressedPackets * 100 / literalPackets) + "%");
    }
}
