package cn.chyuan.ai.domain.protokernel.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Proto schema 描述（工单 0689 CD3，protobuf 思想）。
 * 字段号→名称/类型/出现规则（SINGLE/REPEATED/MAP）/builder API/
 * 重复字段号与重复名拒绝/MAP 键值类型描述。
 */
public final class ProtoSchema {

    public enum Label { SINGLE, REPEATED, MAP }

    public enum Type {
        INT32, INT64, UINT32, UINT64, SINT32, SINT64, BOOL, STRING, BYTES, FLOAT, DOUBLE, MESSAGE
    }

    public record FieldSpec(String name, int number, Type type, Label label, boolean packed,
                            Type keyType, String messageType) {

        public boolean isMap() {
            return label == Label.MAP;
        }
    }

    public static final class MessageDef {
        private final String name;
        private final Map<Integer, FieldSpec> byNumber = new TreeMap<>();
        private final Map<String, FieldSpec> byName = new LinkedHashMap<>();

        MessageDef(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public Map<Integer, FieldSpec> byNumber() {
            return byNumber;
        }

        public FieldSpec field(String name) {
            return byName.get(name);
        }

        void add(FieldSpec spec) {
            if (byNumber.containsKey(spec.number()) || byName.containsKey(spec.name())) {
                throw new IllegalArgumentException("消息 " + name + " 字段号或名重复：" + spec.number() + "/" + spec.name());
            }
            byNumber.put(spec.number(), spec);
            byName.put(spec.name(), spec);
        }
    }

    private final Map<String, MessageDef> messages = new LinkedHashMap<>();

    public MessageDef message(String name) {
        MessageDef def = messages.get(name);
        if (def == null) {
            throw new IllegalArgumentException("未知消息类型：" + name);
        }
        return def;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ProtoSchema schema = new ProtoSchema();

        public Builder message(String name, FieldSpec... fields) {
            MessageDef def = new MessageDef(name);
            for (FieldSpec field : fields) {
                def.add(field);
            }
            schema.messages.put(name, def);
            return this;
        }

        public ProtoSchema build() {
            for (MessageDef def : schema.messages.values()) {
                for (FieldSpec spec : def.byNumber().values()) {
                    if (spec.type() == Type.MESSAGE && spec.messageType() != null
                            && !schema.messages.containsKey(spec.messageType())) {
                        throw new IllegalArgumentException("消息 " + def.name() + " 引用未定义消息：" + spec.messageType());
                    }
                }
            }
            return schema;
        }
    }

    public static FieldSpec scalar(int number, String name, Type type) {
        return new FieldSpec(name, number, type, Label.SINGLE, false, null, null);
    }

    public static FieldSpec repeated(int number, String name, Type type) {
        return new FieldSpec(name, number, type, Label.REPEATED, false, null, null);
    }

    public static FieldSpec repeatedPacked(int number, String name, Type type) {
        return new FieldSpec(name, number, type, Label.REPEATED, true, null, null);
    }

    public static FieldSpec message(int number, String name, String messageType) {
        return new FieldSpec(name, number, Type.MESSAGE, Label.SINGLE, false, null, messageType);
    }

    public static FieldSpec repeatedMessage(int number, String name, String messageType) {
        return new FieldSpec(name, number, Type.MESSAGE, Label.REPEATED, false, null, messageType);
    }

    /** map<string, V 标量> */
    public static FieldSpec mapString(int number, String name, Type valueType) {
        return new FieldSpec(name, number, valueType, Label.MAP, false, Type.STRING, null);
    }

    /** map<int32, V 标量> */
    public static FieldSpec mapInt32(int number, String name, Type valueType) {
        return new FieldSpec(name, number, valueType, Label.MAP, false, Type.INT32, null);
    }

    /** map<string, 消息> */
    public static FieldSpec mapMessage(int number, String name, String messageType) {
        return new FieldSpec(name, number, Type.MESSAGE, Label.MAP, false, Type.STRING, messageType);
    }
}
