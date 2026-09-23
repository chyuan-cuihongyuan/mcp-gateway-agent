package cn.chyuan.ai.domain.protokernel.service;

/**
 * 线格式基元（工单 0687-0688 CD1-CD2，protobuf 思想）。
 * 64 位 varint 编解码（≤10 字节、截断/溢出拒绝）/sint32·sint64 zigzag 映射/
 * tag=field&lt;&lt;3|wire_type 组包解包/wire types（VARINT/FIX64/LEN/FIX32）/
 * 小端 fixed32·64/非法线型拒绝。
 */
public final class Wire {

    public static final int VARINT = 0;
    public static final int FIX64 = 1;
    public static final int LEN = 2;
    public static final int FIX32 = 5;

    private Wire() {
    }

    public static int tag(int fieldNumber, int wireType) {
        if (fieldNumber <= 0 || fieldNumber > 0x3FFFFFFF) {
            throw new IllegalArgumentException("字段号越界：" + fieldNumber);
        }
        return fieldNumber << 3 | wireType;
    }

    public static int fieldNumber(int tag) {
        return tag >>> 3;
    }

    public static int wireType(int tag) {
        return tag & 0x7;
    }

    public static void writeVarint(ByteWriter w, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            w.writeByte((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        w.writeByte((byte) v);
    }

    public static long readVarint(ByteReader r) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (r.atEnd()) {
                throw new IllegalArgumentException("varint 截断");
            }
            byte b = r.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varint 超过 10 字节");
    }

    /** sint32/sint64 zigzag：0,−1,1,−2,2 → 0,1,2,3,4 */
    public static long zigzagEncode(long value) {
        return (value << 1) ^ (value >> 63);
    }

    public static long zigzagDecode(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    public static void writeFixed32(ByteWriter w, int value) {
        w.writeByte((byte) value);
        w.writeByte((byte) (value >>> 8));
        w.writeByte((byte) (value >>> 16));
        w.writeByte((byte) (value >>> 24));
    }

    public static int readFixed32(ByteReader r) {
        if (r.remaining() < 4) {
            throw new IllegalArgumentException("fixed32 截断");
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v |= (r.readByte() & 0xFFL) << (8 * i);
        }
        return v;
    }

    public static void writeFixed64(ByteWriter w, long value) {
        for (int i = 0; i < 8; i++) {
            w.writeByte((byte) (value >>> (8 * i)));
        }
    }

    public static long readFixed64(ByteReader r) {
        if (r.remaining() < 8) {
            throw new IllegalArgumentException("fixed64 截断");
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (r.readByte() & 0xFFL) << (8 * i);
        }
        return v;
    }

    /** 缓冲写入器 */
    public static final class ByteWriter {
        private byte[] buf = new byte[32];
        private int size;

        public void writeByte(byte b) {
            ensure(1);
            buf[size++] = b;
        }

        public void writeBytes(byte[] bytes) {
            ensure(bytes.length);
            System.arraycopy(bytes, 0, buf, size, bytes.length);
            size += bytes.length;
        }

        public int size() {
            return size;
        }

        public byte[] toBytes() {
            byte[] out = new byte[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }

        private void ensure(int n) {
            if (size + n > buf.length) {
                int cap = buf.length;
                while (cap < size + n) {
                    cap *= 2;
                }
                byte[] grown = new byte[cap];
                System.arraycopy(buf, 0, grown, 0, size);
                buf = grown;
            }
        }
    }

    /** 缓冲读取器 */
    public static final class ByteReader {
        private final byte[] buf;
        private int pos;

        public ByteReader(byte[] buf) {
            this.buf = buf;
        }

        public boolean atEnd() {
            return pos >= buf.length;
        }

        public int remaining() {
            return buf.length - pos;
        }

        public int position() {
            return pos;
        }

        public byte[] slice(int from, int to) {
            if (from < 0 || to > buf.length || from > to) {
                throw new IllegalArgumentException("切片越界");
            }
            byte[] out = new byte[to - from];
            System.arraycopy(buf, from, out, 0, to - from);
            return out;
        }

        public byte readByte() {
            if (atEnd()) {
                throw new IllegalArgumentException("读取越界：缓冲截断");
            }
            return buf[pos++];
        }

        public byte[] readBytes(int n) {
            if (remaining() < n) {
                throw new IllegalArgumentException("读取越界：缓冲截断");
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }
    }
}
