package cn.chyuan.ai.domain.protokernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 消息编解码（工单 0689-0692 CD3-CD6，protobuf 思想）。
 * LEN 嵌套消息递归/int32 负数 10 字节符号扩展语义/repeated 逐项与
 * packed LEN 双形态互读/map entry（key+value，重复 key 后者胜、编码按 key
 * 排序确定性）/未知编号字段字节原样保留（解码→再编码不丢）/
 * 缺省标量补默认值/非法线型拒绝。
 */
public final class ProtoCodec {

    /** 解码产物：已知字段值表 + 未知字段原始字节 */
    public static final class ProtoObject {

        public record Unknown(int fieldNumber, int wireType, byte[] raw) {
        }

        private final Map<String, Object> fields = new LinkedHashMap<>();
        private final List<Unknown> unknowns = new ArrayList<>();

        public Map<String, Object> fields() {
            return fields;
        }

        public List<Unknown> unknowns() {
            return unknowns;
        }

        /** 再编码：已知字段按 schema 重排 + 未知字段原样追加 */
        public byte[] reEncode(ProtoSchema schema, String messageName) {
            Wire.ByteWriter w = new Wire.ByteWriter();
            encode(schema, messageName, fields, w);
            for (Unknown unknown : unknowns) {
                w.writeBytes(unknown.raw());
            }
            return w.toBytes();
        }
    }

    private ProtoCodec() {
    }

    public static byte[] encode(ProtoSchema schema, String messageName, Map<String, Object> values) {
        Wire.ByteWriter w = new Wire.ByteWriter();
        encode(schema, messageName, values, w);
        return w.toBytes();
    }

    public static ProtoObject decode(ProtoSchema schema, String messageName, byte[] wire) {
        if (schema == null || messageName == null || wire == null) {
            throw new IllegalArgumentException("编解码入参不得为 null");
        }
        ProtoObject obj = new ProtoObject();
        Wire.ByteReader r = new Wire.ByteReader(wire);
        decodeInto(schema, schema.message(messageName), r, obj.fields(), obj.unknowns(), messageName);
        applyDefaults(schema.message(messageName), obj.fields());
        return obj;
    }

    // ---------------------------------------------------------------- 编码

    private static void encode(ProtoSchema schema, ProtoSchema.MessageDef def,
                               Map<String, Object> values, Wire.ByteWriter w) {
        for (ProtoSchema.FieldSpec spec : def.byNumber().values()) {
            Object value = values.get(spec.name());
            if (value == null) {
                continue;
            }
            if (spec.isMap()) {
                encodeMap(schema, w, spec, (Map<?, ?>) value);
            } else if (spec.label() == ProtoSchema.Label.REPEATED) {
                encodeRepeated(schema, w, spec, (List<?>) value);
            } else {
                writeTaggedField(schema, w, spec.number(), spec, value);
            }
        }
    }

    private static void encode(ProtoSchema schema, String messageName,
                               Map<String, Object> values, Wire.ByteWriter w) {
        encode(schema, schema.message(messageName), values, w);
    }

    private static void encodeRepeated(ProtoSchema schema, Wire.ByteWriter w,
                                       ProtoSchema.FieldSpec spec, List<?> list) {
        if (isNumeric(spec.type())) {
            if (spec.packed()) {
                Wire.ByteWriter payload = new Wire.ByteWriter();
                for (Object item : list) {
                    writeScalarPayload(payload, spec.type(), item, schema, null);
                }
                Wire.writeVarint(w, Wire.tag(spec.number(), Wire.LEN));
                Wire.writeVarint(w, payload.size());
                w.writeBytes(payload.toBytes());
                return;
            }
            for (Object item : list) {
                writeTaggedField(schema, w, spec.number(), spec, item);
            }
            return;
        }
        for (Object item : list) {
            writeTaggedField(schema, w, spec.number(), spec, item);
        }
    }

    private static void encodeMap(ProtoSchema schema, Wire.ByteWriter w,
                                  ProtoSchema.FieldSpec spec, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            Wire.ByteWriter entry = new Wire.ByteWriter();
            ProtoSchema.FieldSpec keySpec = ProtoSchema.scalar(1, "key", spec.keyType());
            writeTaggedField(schema, entry, 1, keySpec, parseMapKey(spec.keyType(), e.getKey()));
            if (spec.type() == ProtoSchema.Type.MESSAGE) {
                ProtoSchema.FieldSpec valueSpec = ProtoSchema.message(2, "value", spec.messageType());
                writeTaggedField(schema, entry, 2, valueSpec, e.getValue());
            } else {
                ProtoSchema.FieldSpec valueSpec = ProtoSchema.scalar(2, "value", spec.type());
                writeTaggedField(schema, entry, 2, valueSpec, e.getValue());
            }
            Wire.writeVarint(w, Wire.tag(spec.number(), Wire.LEN));
            Wire.writeVarint(w, entry.size());
            w.writeBytes(entry.toBytes());
        }
    }

    private static Object parseMapKey(ProtoSchema.Type keyType, String key) {
        if (keyType == ProtoSchema.Type.INT32 || keyType == ProtoSchema.Type.INT64) {
            return Long.parseLong(key);
        }
        return key;
    }

    private static void writeTaggedField(ProtoSchema schema, Wire.ByteWriter w, int number,
                                         ProtoSchema.FieldSpec spec, Object value) {
        ProtoSchema.Type type = spec.type();
        if (type == ProtoSchema.Type.MESSAGE) {
            byte[] payload = value instanceof ProtoObject po
                    ? po.reEncode(schema, spec.messageType())
                    : encode(schema, spec.messageType(), (Map<String, Object>) value);
            Wire.writeVarint(w, Wire.tag(number, Wire.LEN));
            Wire.writeVarint(w, payload.length);
            w.writeBytes(payload);
            return;
        }
        int wireType = wireTypeOf(type);
        Wire.writeVarint(w, Wire.tag(number, wireType));
        writeScalarPayload(w, type, value, schema, spec);
    }

    @SuppressWarnings("unchecked")
    private static void writeScalarPayload(Wire.ByteWriter w, ProtoSchema.Type type,
                                           Object value, ProtoSchema schema, ProtoSchema.FieldSpec spec) {
        long l;
        switch (type) {
            case INT32, INT64 -> {
                l = ((Number) value).longValue();
                Wire.writeVarint(w, l);
            }
            case UINT32 -> Wire.writeVarint(w, ((Number) value).longValue() & 0xFFFFFFFFL);
            case UINT64 -> Wire.writeVarint(w, ((Number) value).longValue());
            case SINT32, SINT64 -> Wire.writeVarint(w, Wire.zigzagEncode(((Number) value).longValue()));
            case BOOL -> Wire.writeVarint(w, Boolean.TRUE.equals(value) ? 1 : 0);
            case STRING -> {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                Wire.writeVarint(w, bytes.length);
                w.writeBytes(bytes);
            }
            case BYTES -> {
                byte[] bytes = (byte[]) value;
                Wire.writeVarint(w, bytes.length);
                w.writeBytes(bytes);
            }
            case FLOAT -> Wire.writeFixed32(w, Float.floatToRawIntBits(((Number) value).floatValue()));
            case DOUBLE -> Wire.writeFixed64(w, Double.doubleToLongBits(((Number) value).doubleValue()));
            default -> throw new IllegalArgumentException("标量载荷不支持类型 " + type);
        }
    }

    // ---------------------------------------------------------------- 解码

    private static void decodeInto(ProtoSchema schema, ProtoSchema.MessageDef def,
                                   Wire.ByteReader r, Map<String, Object> fields,
                                   List<ProtoObject.Unknown> unknowns, String messageName) {
        while (!r.atEnd()) {
            int remainingBefore = r.remaining();
            long tagValue = Wire.readVarint(r);
            int tag = (int) tagValue;
            int number = Wire.fieldNumber(tag);
            int wireType = Wire.wireType(tag);
            ProtoSchema.FieldSpec spec = def.byNumber().get(number);
            if (spec == null) {
                // 未知编号字段：按线型读出后整段原始字节保留（tag+载荷）
                switch (wireType) {
                    case Wire.VARINT -> Wire.readVarint(r);
                    case Wire.FIX64 -> r.readBytes(8);
                    case Wire.LEN -> {
                        int len = (int) Wire.readVarint(r);
                        r.readBytes(len);
                    }
                    case Wire.FIX32 -> r.readBytes(4);
                    default -> throw new IllegalArgumentException("未知字段 " + number + " 遇到非法线型 " + wireType);
                }
                unknowns.add(new ProtoObject.Unknown(number, wireType, r.slice(r.position() - (remainingBefore - r.remaining()), r.position())));
                continue;
            }
            int expectedWire = spec.isMap() ? Wire.LEN : wireTypeOf(spec.type());
            if (wireType != expectedWire && !(spec.label() == ProtoSchema.Label.REPEATED && wireType == Wire.LEN)) {
                throw new IllegalArgumentException("字段 " + spec.name() + " 线型不符：期望 "
                        + expectedWire + " 实为 " + wireType);
            }
            if (spec.isMap()) {
                decodeMap(schema, r, spec, fields);
            } else if (spec.label() == ProtoSchema.Label.REPEATED) {
                decodeRepeated(schema, r, spec, wireType, fields);
            } else {
                fields.put(spec.name(), decodeValue(schema, r, spec, wireType));
            }
        }
    }

    private static void decodeMap(ProtoSchema schema, Wire.ByteReader r,
                                  ProtoSchema.FieldSpec spec, Map<String, Object> fields) {
        int len = (int) Wire.readVarint(r);
        Wire.ByteReader entry = new Wire.ByteReader(r.readBytes(len));
        Object key = null;
        Object value = null;
        while (!entry.atEnd()) {
            long tagValue = Wire.readVarint(entry);
            int field = Wire.fieldNumber((int) tagValue);
            int wireType = Wire.wireType((int) tagValue);
            if (field == 1) {
                key = decodeScalar(spec.keyType(), entry);
            } else if (field == 2) {
                if (spec.type() == ProtoSchema.Type.MESSAGE) {
                    int vlen = (int) Wire.readVarint(entry);
                    value = decode(schema, spec.messageType(), entry.readBytes(vlen));
                } else {
                    value = decodeScalar(spec.type(), entry);
                }
            } else {
                throw new IllegalArgumentException("map entry 非法字段号 " + field);
            }
        }
        Map<Object, Object> map = (Map<Object, Object>) fields.computeIfAbsent(spec.name(),
                k -> new TreeMap<>());
        if (key != null) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void decodeRepeated(ProtoSchema schema, Wire.ByteReader r,
                                       ProtoSchema.FieldSpec spec, int wireType,
                                       Map<String, Object> fields) {
        List<Object> list = (List<Object>) fields.computeIfAbsent(spec.name(), k -> new ArrayList<>());
        if (wireType == Wire.LEN && isNumeric(spec.type())) {
            int len = (int) Wire.readVarint(r);
            Wire.ByteReader packed = new Wire.ByteReader(r.readBytes(len));
            while (!packed.atEnd()) {
                list.add(decodeScalar(spec.type(), packed));
            }
            return;
        }
        list.add(decodeValue(schema, r, spec, wireType));
    }

    private static Object decodeValue(ProtoSchema schema, Wire.ByteReader r,
                                      ProtoSchema.FieldSpec spec, int wireType) {
        if (spec.type() == ProtoSchema.Type.MESSAGE) {
            int len = (int) Wire.readVarint(r);
            return decode(schema, spec.messageType(), r.readBytes(len));
        }
        return decodeScalar(spec.type(), r);
    }

    private static Object decodeScalar(ProtoSchema.Type type, Wire.ByteReader r) {
        return switch (type) {
            case INT32 -> (long) (int) Wire.readVarint(r);
            case INT64 -> Wire.readVarint(r);
            case UINT32 -> Wire.readVarint(r) & 0xFFFFFFFFL;
            case UINT64 -> Wire.readVarint(r);
            case SINT32 -> (long) (int) Wire.zigzagDecode(Wire.readVarint(r));
            case SINT64 -> Wire.zigzagDecode(Wire.readVarint(r));
            case BOOL -> Wire.readVarint(r) != 0;
            case STRING -> new String(readLenBytes(r), StandardCharsets.UTF_8);
            case BYTES -> readLenBytes(r);
            case FLOAT -> Float.intBitsToFloat(Wire.readFixed32(r));
            case DOUBLE -> Double.longBitsToDouble(Wire.readFixed64(r));
            default -> throw new IllegalArgumentException("标量解码不支持类型 " + type);
        };
    }

    private static byte[] readLenBytes(Wire.ByteReader r) {
        int len = (int) Wire.readVarint(r);
        return r.readBytes(len);
    }

    private static void applyDefaults(ProtoSchema.MessageDef def, Map<String, Object> fields) {
        for (ProtoSchema.FieldSpec spec : def.byNumber().values()) {
            if (fields.containsKey(spec.name()) || spec.label() != ProtoSchema.Label.SINGLE
                    || spec.type() == ProtoSchema.Type.MESSAGE) {
                continue;
            }
            Object defaultValue = switch (spec.type()) {
                case BOOL -> Boolean.FALSE;
                case STRING -> "";
                case BYTES -> new byte[0];
                case FLOAT, DOUBLE -> 0.0d;
                default -> 0L;
            };
            fields.put(spec.name(), defaultValue);
        }
    }

    private static int wireTypeOf(ProtoSchema.Type type) {
        return switch (type) {
            case INT32, INT64, UINT32, UINT64, SINT32, SINT64, BOOL -> Wire.VARINT;
            case STRING, BYTES, MESSAGE -> Wire.LEN;
            case FLOAT -> Wire.FIX32;
            case DOUBLE -> Wire.FIX64;
        };
    }

    private static boolean isNumeric(ProtoSchema.Type type) {
        return switch (type) {
            case INT32, INT64, UINT32, UINT64, SINT32, SINT64, BOOL, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }
}
