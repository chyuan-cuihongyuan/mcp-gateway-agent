package cn.chyuan.ai.domain.signkernel.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象签名编排实现（工单 1020 EJ8，minio 思想）。
 * 组合 canonical/string-to-sign/派生/presigned/policy/时钟窗口/分块链；
 * vaultkernel 密钥串形态只读联动：凭据掩码形状（形状数据不 import vaultkernel）。
 */
public final class SignHub implements SignPort {

    private final Map<String, String> keys = new HashMap<>();

    @Override
    public void registerKey(String accessKey, String secretKey) {
        if (accessKey == null || accessKey.isEmpty()) {
            throw new IllegalArgumentException("accessKey 为空");
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("secretKey 缺失");
        }
        keys.put(accessKey, secretKey);
    }

    @Override
    public boolean hasKey(String accessKey) {
        return keys.containsKey(accessKey);
    }

    @Override
    public String canonical(String method, String path, Map<String, String> query,
                            Map<String, List<String>> headers, String payloadHash) {
        return CanonicalRequest.build(method, path, query, headers, payloadHash);
    }

    @Override
    public String stringToSign(String timestamp, String scope, String canonicalRequest) {
        return StringToSign.build(timestamp, scope, canonicalRequest);
    }

    @Override
    public String sign(String accessKey, String date, String region, String service, String stringToSign) {
        String secret = keys.get(accessKey);
        if (secret == null) {
            throw new IllegalStateException("凭据未注册: " + accessKey);
        }
        return SignatureDerive.derive(secret, date, region, service, stringToSign);
    }

    @Override
    public void verifyPresigned(String accessKey, Map<String, String> params,
                                long serverNowMs, long toleranceMs) {
        PresignedUrl.requireParam(params, PresignedUrl.P_CREDENTIAL);
        String credential = params.get(PresignedUrl.P_CREDENTIAL);
        if (!credential.startsWith(accessKey + "/")) {
            throw new IllegalStateException("凭据主体不匹配: " + credential);
        }
        String[] parts = credential.split("/");
        if (parts.length != 5) {
            throw new IllegalArgumentException("凭据范围须五段: " + credential);
        }
        PresignedUrl.verify(params, serverNowMs);
        checkSkew(PresignedUrl.parseAmzTime(params.get(PresignedUrl.P_DATE)), serverNowMs, toleranceMs);
        String date = parts[1];
        String region = parts[2];
        String service = parts[3];
        String canonical = CanonicalRequest.build("GET", "/bucket/object",
                stripSignatureParams(params), Map.of("host", List.of("minio.local")),
                "UNSIGNED-PAYLOAD");
        String stringToSign = StringToSign.build(params.get(PresignedUrl.P_DATE),
                date + "/" + region + "/" + service + "/aws4_request", canonical);
        String expected = sign(accessKey, date, region, service, stringToSign);
        PresignedUrl.matchSignature(expected, params.get(PresignedUrl.P_SIGNATURE));
    }

    private static Map<String, String> stripSignatureParams(Map<String, String> params) {
        Map<String, String> kept = new HashMap<>(params);
        kept.remove(PresignedUrl.P_SIGNATURE);
        return kept;
    }

    @Override
    public void checkSkew(long requestMs, long serverNowMs, long toleranceMs) {
        ClockSkew.check(requestMs, serverNowMs, toleranceMs);
    }

    @Override
    public ChunkChain startChunk(String seedSignature) {
        return ChunkChain.start(seedSignature);
    }

    @Override
    public String keyShape(String accessKey) {
        String secret = keys.get(accessKey);
        if (secret == null) {
            throw new IllegalStateException("凭据未注册: " + accessKey);
        }
        String tail = accessKey.length() <= 4 ? accessKey : accessKey.substring(accessKey.length() - 4);
        return "vault:ak-" + "*".repeat(Math.max(0, accessKey.length() - 4)) + tail
                + "/sk-" + secret.length() + "B";
    }
}
