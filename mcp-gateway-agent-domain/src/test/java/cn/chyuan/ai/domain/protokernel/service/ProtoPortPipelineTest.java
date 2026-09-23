package cn.chyuan.ai.domain.protokernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProtoPort 组合管线测试（工单 0694 CD8，protobuf 思想）。
 * 对象→wire→对象往返自校验（再编码字节一致）/hex 载荷 msgkernel 只读联动
 * 形态/非法入参拒绝。
 */
class ProtoPortPipelineTest {

    static final ProtoSchema SCHEMA = ProtoSchema.builder()
            .message("Payload", ProtoSchema.scalar(1, "seq", ProtoSchema.Type.INT64),
                    ProtoSchema.scalar(2, "body", ProtoSchema.Type.STRING),
                    ProtoSchema.message(3, "trace", "Trace"))
            .message("Trace", ProtoSchema.scalar(1, "traceId", ProtoSchema.Type.STRING))
            .build();

    @Test
    void portRoundtripVerifyAndHexSegment() {
        ProtoPort port = new ProtoPort.InMemoryProto();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", "t-1");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", 9L);
        payload.put("body", "载荷");
        payload.put("trace", trace);
        ProtoCodec.ProtoObject decoded = port.roundtripVerify(SCHEMA, "Payload", payload);
        assertEquals(9L, decoded.fields().get("seq"));
        assertEquals("载荷", decoded.fields().get("body"));
        ProtoCodec.ProtoObject inner = (ProtoCodec.ProtoObject) decoded.fields().get("trace");
        assertEquals("t-1", inner.fields().get("traceId"));

        byte[] wire = port.encode(SCHEMA, "Payload", payload);
        String segment = port.payloadAsSegment(wire);
        assertTrue(segment.startsWith("proto:"), "msgkernel 载荷段形态前缀");
        assertEquals(wire.length * 2, segment.length() - 6, "hex 双字符每字节");
        assertThrows(IllegalArgumentException.class, () -> port.payloadAsSegment(null));
    }

    @Test
    void portUnknownCarryingThroughPort() {
        ProtoPort port = new ProtoPort.InMemoryProto();
        ProtoSchema extended = ProtoSchema.builder()
                .message("Payload", ProtoSchema.scalar(1, "seq", ProtoSchema.Type.INT64),
                        ProtoSchema.scalar(77, "extra", ProtoSchema.Type.BOOL))
                .build();
        byte[] wire = port.encode(extended, "Payload", Map.of("seq", 1L, "extra", true));
        ProtoCodec.ProtoObject view = port.decode(SCHEMA, "Payload", wire);
        assertEquals(1, view.unknowns().size());
        byte[] again = view.reEncode(SCHEMA, "Payload");
        ProtoCodec.ProtoObject recovered = port.decode(extended, "Payload", again);
        assertEquals(Boolean.TRUE, recovered.fields().get("extra"), "再编码后扩展字段可读");
    }
}
