package cn.chyuan.ai.domain.signkernel.service;

import java.nio.charset.StandardCharsets;

/**
 * 分块签名链（工单 1019 EJ7，minio chunked upload 思想）。
 * 种子签名起步/序号递增/乱序拒绝/链式传递。
 */
public final class ChunkChain {

    private String prevSignature;
    private long expected;

    private ChunkChain(String seedSignature) {
        if (seedSignature == null || seedSignature.isEmpty()) {
            throw new IllegalArgumentException("种子签名为空");
        }
        this.prevSignature = seedSignature;
    }

    /** 种子签名起步 */
    public static ChunkChain start(String seedSignature) {
        return new ChunkChain(seedSignature);
    }

    /** 追加分块：序号须从 0 递增（乱序拒绝）；签名 = hex(hmac(前签名, 序号:数据)) */
    public synchronized String append(long chunkNumber, String data) {
        if (chunkNumber != expected) {
            throw new IllegalStateException("分块乱序: 期望 " + expected + " 实际 " + chunkNumber);
        }
        if (data == null) {
            throw new IllegalArgumentException("分块数据为空");
        }
        byte[] prev = hexToBytes(prevSignature);
        byte[] signature = SignatureDerive.hmac(prev, chunkNumber + ":" + data);
        prevSignature = SignatureDerive.hex(signature);
        expected++;
        return prevSignature;
    }

    public synchronized String current() {
        return prevSignature;
    }

    public synchronized long appended() {
        return expected;
    }

    static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
