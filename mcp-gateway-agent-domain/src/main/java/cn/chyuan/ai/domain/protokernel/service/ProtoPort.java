package cn.chyuan.ai.domain.protokernel.service;

import java.util.Map;

/**
 * Proto 端口+组合管线（工单 0694 CD8）。
 * 对象→wire→对象往返自校验；与 msgkernel 只读联动（消息载荷 hex 形态，
 * 泛型字符串不 import msgkernel，不改其任何类）/
 * proto-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ProtoPort {

    /** 编解码统计：编码字节/解码字段数/未知字段数/耗时 */
    record Stat(int encodedBytes, int decodedFields, int unknownFields, long costMs) {
    }

    byte[] encode(ProtoSchema schema, String message, Map<String, Object> values);

    ProtoCodec.ProtoObject decode(ProtoSchema schema, String message, byte[] wire);

    /** 往返自校验：编码→解码→再编码必须字节一致，返回解码对象 */
    ProtoCodec.ProtoObject roundtripVerify(ProtoSchema schema, String message, Map<String, Object> values);

    /** 与 msgkernel 只读联动：线格式载荷输出为 hex 段形态 */
    String payloadAsSegment(byte[] wire);

    /** 内存假实现：Wire+ProtoSchema+ProtoCodec 全链 */
    class InMemoryProto implements ProtoPort {

        @Override
        public byte[] encode(ProtoSchema schema, String message, Map<String, Object> values) {
            return ProtoCodec.encode(schema, message, values);
        }

        @Override
        public ProtoCodec.ProtoObject decode(ProtoSchema schema, String message, byte[] wire) {
            return ProtoCodec.decode(schema, message, wire);
        }

        @Override
        public ProtoCodec.ProtoObject roundtripVerify(ProtoSchema schema, String message, Map<String, Object> values) {
            byte[] wire = ProtoCodec.encode(schema, message, values);
            ProtoCodec.ProtoObject decoded = ProtoCodec.decode(schema, message, wire);
            byte[] again = decoded.reEncode(schema, message);
            if (!java.util.Arrays.equals(wire, again)) {
                throw new IllegalStateException("往返再编码字节不一致：内核自校验失败");
            }
            return decoded;
        }

        @Override
        public String payloadAsSegment(byte[] wire) {
            if (wire == null) {
                throw new IllegalArgumentException("载荷不得为 null");
            }
            StringBuilder sb = new StringBuilder("proto:");
            for (byte b : wire) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}
