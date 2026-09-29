package cn.chyuan.ai.domain.signkernel.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对象签名内核测试（工单 1013-1020 EJ1-EJ8，minio SigV4 思想）。
 * canonical request/string-to-sign/签名派生/presigned URL/POST policy/时钟窗口/分块签名链/端口组合管线。
 */
class SignKernelTest {

    @Test
    void canonicalRequest() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("b", "2");
        query.put("a", "1");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Host", List.of("minio.local"));
        headers.put("X-Amz-Date", List.of("20260929T120000Z"));
        String canonical = CanonicalRequest.build("get", "/bucket/object", query, headers, "HASH");
        String[] lines = canonical.split("\n", -1);
        assertEquals("GET", lines[0], "方法大写规范化");
        assertEquals("/bucket/object", lines[1]);
        assertEquals("a=1&b=2", lines[2], "查询按键排序");
        assertEquals("host:minio.local", lines[3], "头小写排序");
        assertEquals("x-amz-date:20260929T120000Z", lines[4], "头小写排序续行");
        assertEquals("host;x-amz-date", lines[5], "签名头清单按序");
        assertEquals("HASH", lines[6]);

        Map<String, List<String>> multi = new LinkedHashMap<>();
        multi.put("cache-control", List.of(" no-cache ", "max-age=1"));
        String multiCanonical = CanonicalRequest.build("PUT", "/p ath", null, multi, "H");
        assertTrue(multiCanonical.contains("cache-control:no-cache,max-age=1"), "同名多值保留序合并");
        assertTrue(multiCanonical.contains("/p%20ath"), "路径空格转义");

        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.build("", "/", null, headers, "H"), "空方法拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.build("GET", "", null, headers, "H"), "空路径拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.build("GET", "/", null, headers, ""), "空载荷哈希拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.build("GET", "/", null, new LinkedHashMap<>(), "H"), "空签名头拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.build("GET", "/", null, Map.of("h", List.of()), "H"), "空头值拒绝");
    }

    @Test
    void stringToSign() {
        String canonical = CanonicalRequest.build("GET", "/o", null,
                Map.of("host", List.of("h")), "H");
        String hashed = StringToSign.hash(canonical);
        assertEquals(64, hashed.length());
        assertEquals(hashed.toLowerCase(), hashed, "哈希小写十六进制");
        assertEquals(hashed, StringToSign.hash(canonical), "哈希确定性");

        String sts = StringToSign.build("20260929T120000Z", "20260929/cn-north-1/s3/aws4_request", canonical);
        String[] lines = sts.split("\n", -1);
        assertEquals("AWS4-HMAC-SHA256", lines[0]);
        assertEquals("20260929T120000Z", lines[1]);
        assertEquals("20260929/cn-north-1/s3/aws4_request", lines[2]);
        assertEquals(hashed, lines[3], "哈希落定第四行");

        assertThrows(IllegalArgumentException.class, () -> StringToSign.build(null, "a/b/c/d", canonical), "缺时间拒绝");
        assertThrows(IllegalArgumentException.class, () -> StringToSign.build("", "a/b/c/d", canonical), "空时间拒绝");
        assertThrows(IllegalArgumentException.class, () -> StringToSign.build("t", "a/b/c", canonical), "范围三段拒绝");
        assertThrows(IllegalArgumentException.class, () -> StringToSign.build("t", "a//c/d", canonical), "范围空段拒绝");
    }

    @Test
    void signatureDerive() {
        String sig1 = SignatureDerive.derive("secret", "20260929", "cn-north-1", "s3", "sts");
        assertEquals(64, sig1.length());
        assertEquals(sig1, SignatureDerive.derive("secret", "20260929", "cn-north-1", "s3", "sts"), "派生确定性");
        assertNotEquals(sig1, SignatureDerive.derive("secret", "20260929", "cn-east-1", "s3", "sts"), "region 变签名变");
        assertNotEquals(sig1, SignatureDerive.derive("secret", "20260929", "cn-north-1", "iam", "sts"), "service 变签名变");
        assertNotEquals(sig1, SignatureDerive.derive("other", "20260929", "cn-north-1", "s3", "sts"), "secret 变签名变");
        assertThrows(IllegalArgumentException.class,
                () -> SignatureDerive.derive("", "20260929", "r", "s", "sts"), "secret 缺失拒绝");
    }

    @Test
    void presignedUrl() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        long requestMs = LocalDateTime.parse("20260929T120000Z", fmt)
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PresignedUrl.P_DATE, "20260929T120000Z");
        params.put(PresignedUrl.P_EXPIRES, "60");
        params.put(PresignedUrl.P_SIGNATURE, "abc123");
        params.put(PresignedUrl.P_CREDENTIAL, "AKID/20260929/cn-north-1/s3/aws4_request");
        PresignedUrl.verify(params, requestMs + 5_000);
        PresignedUrl.verify(params, requestMs + 60_000);
        assertThrows(IllegalStateException.class,
                () -> PresignedUrl.verify(params, requestMs + 60_001), "过期拒绝");

        Map<String, String> missing = new LinkedHashMap<>(params);
        missing.remove(PresignedUrl.P_SIGNATURE);
        assertThrows(IllegalArgumentException.class, () -> PresignedUrl.verify(missing, requestMs), "参数缺失拒绝");

        Map<String, String> badExpires = new LinkedHashMap<>(params);
        badExpires.put(PresignedUrl.P_EXPIRES, "-1");
        assertThrows(IllegalArgumentException.class, () -> PresignedUrl.verify(badExpires, requestMs), "Expires 为负拒绝");

        assertThrows(IllegalStateException.class,
                () -> PresignedUrl.matchSignature("abc", "abd"), "签名不匹配拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PresignedUrl.parseAmzTime("bad-date"), "时间格式非法拒绝");
        assertEquals(requestMs, PresignedUrl.parseAmzTime("20260929T120000Z"));
        assertEquals("20260929T120000Z", PresignedUrl.formatAmzTime(requestMs));
    }

    @Test
    void postPolicy() {
        Map<String, String> form = Map.of("bucket", "photos", "key", "user/1.png", "acl", "private");
        PostPolicy.validate(List.of(
                new PostPolicy.Condition("eq", "bucket", "photos"),
                new PostPolicy.Condition("starts-with", "key", "user/"),
                new PostPolicy.Condition("eq", "acl", "private")), form);

        assertThrows(IllegalStateException.class, () -> PostPolicy.validate(List.of(
                new PostPolicy.Condition("eq", "bucket", "other")), form), "eq 不满足拒绝");
        assertThrows(IllegalStateException.class, () -> PostPolicy.validate(List.of(
                new PostPolicy.Condition("starts-with", "key", "admin/")), form), "前缀不满足拒绝");
        assertThrows(IllegalStateException.class, () -> PostPolicy.validate(List.of(
                new PostPolicy.Condition("eq", "missing", "x")), form), "字段缺失拒绝");
        assertThrows(IllegalArgumentException.class, () -> PostPolicy.validate(null, form), "空条件拒绝");
        assertThrows(IllegalArgumentException.class, () -> new PostPolicy.Condition("gt", "f", "v"), "未知算子拒绝");
    }

    @Test
    void clockSkew() {
        long now = 1_000_000L;
        ClockSkew.check(now - 300_000, now, 300_000);
        ClockSkew.check(now + 300_000, now, 300_000);
        assertThrows(IllegalStateException.class,
                () -> ClockSkew.check(now - 300_001, now, 300_000), "滞后超窗拒绝");
        assertThrows(IllegalStateException.class,
                () -> ClockSkew.check(now + 300_001, now, 300_000), "超前超窗拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> ClockSkew.check(now, now, -1), "负容差拒绝");
    }

    @Test
    void chunkChain() {
        String seed = SignatureDerive.derive("secret", "20260929", "r", "s", "seed");
        ChunkChain chain = ChunkChain.start(seed);
        assertEquals(0, chain.appended());
        String sig0 = chain.append(0, "chunk-0");
        assertEquals(64, sig0.length());
        String sig1 = chain.append(1, "chunk-1");
        assertNotEquals(sig0, sig1, "链式传递签名递进");
        assertEquals(sig1, chain.current());
        assertThrows(IllegalStateException.class, () -> chain.append(3, "skip"), "乱序拒绝");
        String sig2 = chain.append(2, "chunk-2");
        assertNotEquals(sig1, sig2, "回正后继续链式递进");
        assertEquals(3, chain.appended());

        ChunkChain replay = ChunkChain.start(seed);
        assertEquals(sig0, replay.append(0, "chunk-0"), "同种子同数据签名确定性");
        assertThrows(IllegalArgumentException.class, () -> ChunkChain.start(""), "空种子拒绝");
        assertThrows(IllegalArgumentException.class, () -> chain.append(3, null), "空数据拒绝");
    }

    @Test
    void signPortPipeline() {
        SignPort port = SignPort.inMemory();
        port.registerKey("AKID-example", "wJalrXUtnFEMI");
        assertTrue(port.hasKey("AKID-example"));
        port.registerKey("AKID-example", "rotated-secret");
        assertThrows(IllegalArgumentException.class, () -> port.registerKey("", "s"), "空 accessKey 拒绝");

        // canonical·sign
        String canonical = port.canonical("GET", "/bucket/object", null,
                Map.of("Host", List.of("minio.local")), "UNSIGNED-PAYLOAD");
        String sts = port.stringToSign("20260929T120000Z", "20260929/cn-north-1/s3/aws4_request", canonical);
        String signature = port.sign("AKID-example", "20260929", "cn-north-1", "s3", sts);
        assertEquals(64, signature.length());
        assertThrows(IllegalStateException.class,
                () -> port.sign("ghost", "20260929", "r", "s", sts), "凭据未注册拒绝");

        // presigned 三关：与 verifyPresigned 同形状构造（query=参数减签名）→放行→篡改拒绝
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        long requestMs = LocalDateTime.parse("20260929T120000Z", fmt)
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PresignedUrl.P_DATE, "20260929T120000Z");
        params.put(PresignedUrl.P_EXPIRES, "300");
        params.put(PresignedUrl.P_CREDENTIAL, "AKID-example/20260929/cn-north-1/s3/aws4_request");
        String presignedCanonical = port.canonical("GET", "/bucket/object", params,
                Map.of("Host", List.of("minio.local")), "UNSIGNED-PAYLOAD");
        String presignedSts = port.stringToSign("20260929T120000Z",
                "20260929/cn-north-1/s3/aws4_request", presignedCanonical);
        params.put(PresignedUrl.P_SIGNATURE,
                port.sign("AKID-example", "20260929", "cn-north-1", "s3", presignedSts));
        port.verifyPresigned("AKID-example", params, requestMs + 1_000, 300_000);

        Map<String, String> tampered = new LinkedHashMap<>(params);
        tampered.put(PresignedUrl.P_EXPIRES, "301");
        assertThrows(IllegalStateException.class,
                () -> port.verifyPresigned("AKID-example", tampered, requestMs + 1_000, 300_000), "篡改参数签名不匹配拒绝");

        port.checkSkew(requestMs, requestMs + 300_000, 300_000);
        assertThrows(IllegalStateException.class,
                () -> port.checkSkew(requestMs, requestMs + 300_001, 300_000), "偏斜超窗拒绝");

        // 分块链 + vaultkernel 密钥形态只读联动
        ChunkChain chain = port.startChunk(signature);
        assertEquals(64, chain.append(0, "part").length());
        String shape = port.keyShape("AKID-example");
        assertTrue(shape.startsWith("vault:ak-"), "vaultkernel 密钥掩码形状联动");
        assertTrue(shape.endsWith("mple/sk-14B"), "尾四位明文+secret 长度形状: " + shape);
        assertThrows(IllegalStateException.class, () -> port.keyShape("ghost"), "未注册凭据形状拒绝");
    }
}
