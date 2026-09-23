package cn.chyuan.ai.domain.protokernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proto 线格式内核域单测（工单 0687-0693 CD1-CD7，protobuf 思想）。
 * varint·zigzag 边界值/tag 线型/消息编解码与 int32 负数 10 字节语义/
 * repeated 与 packed 两形态互读/map entry 确定性与重复 key 后者胜/
 * 未知字段单层与嵌套保留（解码→再编码不丢）/缺省默认值与线型不符拒绝。
 */
class ProtoKernelTest {

    static final ProtoSchema SCHEMA = ProtoSchema.builder()
            .message("Inner", ProtoSchema.scalar(1, "code", ProtoSchema.Type.INT32),
                    ProtoSchema.scalar(2, "label", ProtoSchema.Type.STRING))
            .message("Item",
                    ProtoSchema.scalar(1, "id", ProtoSchema.Type.INT64),
                    ProtoSchema.scalar(2, "name", ProtoSchema.Type.STRING),
                    ProtoSchema.scalar(3, "active", ProtoSchema.Type.BOOL),
                    ProtoSchema.scalar(4, "score", ProtoSchema.Type.DOUBLE),
                    ProtoSchema.scalar(5, "delta", ProtoSchema.Type.SINT32),
                    ProtoSchema.message(6, "inner", "Inner"),
                    ProtoSchema.repeated(7, "tags", ProtoSchema.Type.STRING),
                    ProtoSchema.repeatedPacked(8, "points", ProtoSchema.Type.INT32),
                    ProtoSchema.mapString(9, "counts", ProtoSchema.Type.INT32),
                    ProtoSchema.mapInt32(10, "levels", ProtoSchema.Type.STRING))
            .build();

    @Test
    void varintZigzagBoundaryValues() {
        for (long v : new long[]{0, 1, 127, 128, 300, 8192, -1, Long.MAX_VALUE}) {
            Wire.ByteWriter w = new Wire.ByteWriter();
            Wire.writeVarint(w, v);
            assertEquals(v, Wire.readVarint(new Wire.ByteReader(w.toBytes())), "varint 往返 " + v);
        }
        Wire.ByteWriter w = new Wire.ByteWriter();
        Wire.writeVarint(w, -1);
        assertEquals(10, w.size(), "负数符号扩展为 10 字节");
        for (long v : new long[]{0, -1, 1, -2, 2, 63, -64, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertEquals(v, Wire.zigzagDecode(Wire.zigzagEncode(v)), "zigzag 往返 " + v);
        }
        assertEquals(1, Wire.zigzagEncode(-1));
        assertEquals(0, Wire.zigzagEncode(0));
        Wire.ByteReader truncated = new Wire.ByteReader(new byte[]{(byte) 0x80});
        assertThrows(IllegalArgumentException.class, () -> Wire.readVarint(truncated), "varint 截断拒绝");
    }

    @Test
    void tagWireTypesAndFixed() {
        assertEquals(8, Wire.tag(1, Wire.VARINT));
        assertEquals(1, Wire.fieldNumber(Wire.tag(1, Wire.LEN)));
        assertEquals(Wire.LEN, Wire.wireType(Wire.tag(1, Wire.LEN)));
        Wire.ByteWriter w = new Wire.ByteWriter();
        Wire.writeFixed32(w, 0x01020304);
        Wire.writeFixed64(w, 0x0102030405060708L);
        Wire.ByteReader r = new Wire.ByteReader(w.toBytes());
        assertEquals(0x01020304, Wire.readFixed32(r));
        assertEquals(0x0102030405060708L, Wire.readFixed64(r));
        assertThrows(IllegalArgumentException.class, () -> Wire.readFixed64(new Wire.ByteReader(new byte[3])));
        assertThrows(IllegalArgumentException.class, () -> Wire.tag(0, Wire.VARINT), "字段号 0 拒绝");
    }

    @Test
    void messageEncodeDecodeScalarsAndNested() {
        Map<String, Object> inner = Map.of("code", 42L, "label", "内层");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", -5L);
        item.put("name", "顶层数值");
        item.put("active", true);
        item.put("score", 2.5d);
        item.put("delta", -7L);
        item.put("inner", inner);
        byte[] wire = ProtoCodec.encode(SCHEMA, "Item", item);
        ProtoCodec.ProtoObject obj = ProtoCodec.decode(SCHEMA, "Item", wire);
        assertEquals(-5L, obj.fields().get("id"), "int64 负数经 64 位 varint 往返");
        assertEquals("顶层数值", obj.fields().get("name"));
        assertEquals(Boolean.TRUE, obj.fields().get("active"));
        assertEquals(2.5d, obj.fields().get("score"));
        assertEquals(-7L, obj.fields().get("delta"), "sint32 zigzag 负数");
        ProtoCodec.ProtoObject decodedInner = (ProtoCodec.ProtoObject) obj.fields().get("inner");
        assertEquals(42L, decodedInner.fields().get("code"));
        assertEquals("内层", decodedInner.fields().get("label"));
        assertTrue(wire.length < 64, "紧凑线格式");
    }

    @Test
    void int32NegativeTenByteSemantics() {
        ProtoSchema schema = ProtoSchema.builder()
                .message("M", ProtoSchema.scalar(1, "v", ProtoSchema.Type.INT32),
                        ProtoSchema.scalar(2, "s", ProtoSchema.Type.SINT32))
                .build();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("v", -1);
        values.put("s", -1);
        byte[] wire = ProtoCodec.encode(schema, "M", values);
        assertEquals(13, wire.length, "int32(-1)=10 字节 + sint32(-1)=1 字节 + 两个 1 字节 tag");
        ProtoCodec.ProtoObject obj = ProtoCodec.decode(schema, "M", wire);
        assertEquals(-1L, obj.fields().get("v"));
        assertEquals(-1L, obj.fields().get("s"));
    }

    @Test
    void repeatedAndPackedBothFormsReadable() {
        ProtoSchema schema = ProtoSchema.builder()
                .message("Packed", ProtoSchema.repeatedPacked(1, "xs", ProtoSchema.Type.INT32))
                .message("Plain", ProtoSchema.repeated(1, "xs", ProtoSchema.Type.INT32))
                .build();
        Map<String, Object> values = Map.of("xs", List.of(1L, 300L, -1L));
        byte[] packedWire = ProtoCodec.encode(schema, "Packed", values);
        assertEquals(List.of(1L, 300L, -1L), ProtoCodec.decode(schema, "Packed", packedWire).fields().get("xs"));
        assertEquals(List.of(1L, 300L, -1L), ProtoCodec.decode(schema, "Plain", packedWire).fields().get("xs"),
                "packed 数据按逐项 schema 互读");
        byte[] plainWire = ProtoCodec.encode(schema, "Plain", values);
        assertEquals(List.of(1L, 300L, -1L), ProtoCodec.decode(schema, "Packed", plainWire).fields().get("xs"),
                "逐项数据按 packed schema 互读");

        ProtoSchema withTags = ProtoSchema.builder()
                .message("T", ProtoSchema.repeated(1, "tags", ProtoSchema.Type.STRING))
                .build();
        ProtoCodec.ProtoObject tagsObj = ProtoCodec.decode(withTags, "T",
                ProtoCodec.encode(withTags, "T", Map.of("tags", List.of("a", "乙"))));
        assertEquals(List.of("a", "乙"), tagsObj.fields().get("tags"));
    }

    @Test
    void mapFieldDeterministicAndLastWins() {
        ProtoSchema schema = ProtoSchema.builder()
                .message("M", ProtoSchema.mapString(1, "counts", ProtoSchema.Type.INT32),
                        ProtoSchema.mapInt32(2, "levels", ProtoSchema.Type.STRING),
                        ProtoSchema.mapMessage(3, "meta", "V"))
                .message("V", ProtoSchema.scalar(1, "v", ProtoSchema.Type.INT64))
                .build();
        Map<String, Object> counts = new java.util.TreeMap<>();
        counts.put("zeta", 26L);
        counts.put("alpha", 1L);
        counts.put("mid", 13L);
        Map<String, Object> values = Map.of("counts", counts);
        byte[] wire1 = ProtoCodec.encode(schema, "M", values);
        byte[] wire2 = ProtoCodec.encode(schema, "M", values);
        assertArrayEquals(wire1, wire2, "map 编码按 key 排序确定性");
        ProtoCodec.ProtoObject obj = ProtoCodec.decode(schema, "M", wire1);
        @SuppressWarnings("unchecked")
        Map<Object, Object> decoded = (Map<Object, Object>) obj.fields().get("counts");
        assertEquals(3, decoded.size());
        assertEquals(1L, decoded.get("alpha"));

        // 重复 key：后者胜（同 key 两条 entry，第二条覆盖）
        byte[] withDup = new byte[]{
                0x0a, 0x05, 0x0a, 0x01, 0x41, 0x10, 0x01,
                0x0a, 0x05, 0x0a, 0x01, 0x41, 0x10, 0x02};
        ProtoCodec.ProtoObject dup = ProtoCodec.decode(schema, "M", withDup);
        @SuppressWarnings("unchecked")
        Map<Object, Object> dupMap = (Map<Object, Object>) dup.fields().get("counts");
        assertEquals(2L, dupMap.get("A"), "重复 key 后者胜");
    }

    @Test
    void unknownFieldsPreservedThroughReencode() {
        ProtoSchema extended = ProtoSchema.builder()
                .message("Item",
                        ProtoSchema.scalar(1, "id", ProtoSchema.Type.INT64),
                        ProtoSchema.scalar(99, "future", ProtoSchema.Type.STRING))
                .build();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 7L);
        values.put("future", "新字段");
        byte[] extendedWire = ProtoCodec.encode(extended, "Item", values);

        ProtoCodec.ProtoObject oldView = ProtoCodec.decode(SCHEMA, "Item", extendedWire);
        assertEquals(7L, oldView.fields().get("id"));
        assertEquals(1, oldView.unknowns().size(), "新字段对旧 schema 为未知字段");
        assertEquals(99, oldView.unknowns().get(0).fieldNumber());
        assertEquals(Wire.LEN, oldView.unknowns().get(0).wireType());

        byte[] reEncoded = oldView.reEncode(SCHEMA, "Item");
        ProtoCodec.ProtoObject newView = ProtoCodec.decode(extended, "Item", reEncoded);
        assertEquals("新字段", newView.fields().get("future"), "未知字段经旧 schema 中转再编码不丢");
    }

    @Test
    void nestedUnknownFieldsPreserved() {
        ProtoSchema extended = ProtoSchema.builder()
                .message("Inner", ProtoSchema.scalar(1, "code", ProtoSchema.Type.INT32),
                        ProtoSchema.scalar(50, "extra", ProtoSchema.Type.INT64))
                .message("Outer", ProtoSchema.message(1, "inner", "Inner"))
                .build();
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", new LinkedHashMap<>(Map.of("code", 3L, "extra", 99L)));
        byte[] wire = ProtoCodec.encode(extended, "Outer", outer);

        ProtoSchema strict = ProtoSchema.builder()
                .message("Inner", ProtoSchema.scalar(1, "code", ProtoSchema.Type.INT32))
                .message("Outer", ProtoSchema.message(1, "inner", "Inner"))
                .build();
        ProtoCodec.ProtoObject strictView = ProtoCodec.decode(strict, "Outer", wire);
        ProtoCodec.ProtoObject innerObj = (ProtoCodec.ProtoObject) strictView.fields().get("inner");
        assertEquals(3L, innerObj.fields().get("code"));
        assertEquals(1, innerObj.unknowns().size(), "嵌套消息内未知字段保留");
        assertEquals(50, innerObj.unknowns().get(0).fieldNumber());

        byte[] reEncoded = strictView.reEncode(strict, "Outer");
        ProtoCodec.ProtoObject back = ProtoCodec.decode(extended, "Outer", reEncoded);
        ProtoCodec.ProtoObject innerBack = (ProtoCodec.ProtoObject) back.fields().get("inner");
        assertEquals(99L, innerBack.fields().get("extra"), "嵌套未知字段经严格 schema 中转不丢");
    }

    @Test
    void compatibilityDefaultsAndWireTypeMismatch() {
        ProtoSchema schema = ProtoSchema.builder()
                .message("M", ProtoSchema.scalar(1, "name", ProtoSchema.Type.STRING),
                        ProtoSchema.scalar(2, "level", ProtoSchema.Type.INT32))
                .build();
        ProtoCodec.ProtoObject obj = ProtoCodec.decode(schema, "M",
                ProtoCodec.encode(schema, "M", Map.of("name", "仅名")));
        assertEquals(0L, obj.fields().get("level"), "缺席标量读默认值");

        ProtoSchema asUint = ProtoSchema.builder()
                .message("M", ProtoSchema.scalar(1, "name", ProtoSchema.Type.STRING),
                        ProtoSchema.scalar(2, "level", ProtoSchema.Type.UINT64))
                .build();
        byte[] wire = ProtoCodec.encode(schema, "M", Map.of("name", "n", "level", 100L));
        ProtoCodec.ProtoObject uintView = ProtoCodec.decode(asUint, "M", wire);
        assertEquals(100L, uintView.fields().get("level"), "varint 族类型演进安全读位");

        ProtoSchema wrongWire = ProtoSchema.builder()
                .message("M", ProtoSchema.scalar(1, "name", ProtoSchema.Type.STRING),
                        ProtoSchema.scalar(2, "level", ProtoSchema.Type.DOUBLE))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> ProtoCodec.decode(wrongWire, "M", wire), "VARINT 换 FIX64 线型不符拒绝");
    }
}
