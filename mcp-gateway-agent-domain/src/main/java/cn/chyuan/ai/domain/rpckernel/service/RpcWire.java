package cn.chyuan.ai.domain.rpckernel.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息帧、deadline 与取消（工单 0908/0910/0911 DC2·DC4·DC5，grpc 思想）。
 * length-prefix 帧编解码与残缺拒绝/绝对时限与子调用取最小传递/取消标记父子传播。
 */
public final class RpcWire {

    private RpcWire() {
    }

    /** length-prefix 帧：4 字节大端长度 + 载荷 */
    public static byte[] encode(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 4);
        int len = payload.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** 流拆帧：长度不足/长度超出剩余字节即残缺拒绝 */
    public static List<byte[]> decode(byte[] stream) {
        List<byte[]> frames = new ArrayList<>();
        int at = 0;
        while (at < stream.length) {
            if (at + 4 > stream.length) {
                throw new IllegalArgumentException("帧头截断");
            }
            int len = ((stream[at] & 0xFF) << 24) | ((stream[at + 1] & 0xFF) << 16)
                    | ((stream[at + 2] & 0xFF) << 8) | (stream[at + 3] & 0xFF);
            if (at + 4 + len > stream.length) {
                throw new IllegalArgumentException("帧载荷截断");
            }
            byte[] frame = new byte[len];
            System.arraycopy(stream, at + 4, frame, 0, len);
            frames.add(frame);
            at += 4 + len;
        }
        return frames;
    }

    /** deadline：绝对步时限 */
    public static final class Deadline {
        private final long expiresAt;

        public Deadline(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public boolean expired(long now) {
            return now >= expiresAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        /** 子调用时限：父时限与 now+timeout 取最小（deadline 传递） */
        public Deadline child(long now, long timeoutSteps) {
            return new Deadline(Math.min(expiresAt, now + timeoutSteps));
        }
    }

    /** 取消上下文：父取消传播到子 */
    public static final class Cancellation {
        private final Cancellation parent;
        private boolean cancelled = false;
        private final List<Cancellation> children = new ArrayList<>();

        public Cancellation() {
            this(null);
        }

        private Cancellation(Cancellation parent) {
            this.parent = parent;
        }

        public Cancellation child() {
            Cancellation c = new Cancellation(this);
            children.add(c);
            if (cancelled) {
                c.cancelled = true;
            }
            return c;
        }

        public void cancel() {
            cancelled = true;
            for (Cancellation c : children) {
                c.cancel();
            }
        }

        public boolean cancelled() {
            return cancelled || (parent != null && parent.cancelled());
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
