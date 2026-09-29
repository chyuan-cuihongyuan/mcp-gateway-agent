package cn.chyuan.ai.domain.signkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 对象签名端口（工单 1020 EJ8，minio 思想）。
 * canonical·sign·verify 入口统一编排/与 vaultkernel 密钥串作凭据形态只读联动（泛型形状串不 import）/
 * sign-kernel.enabled 默认关（开启才改变行为）。
 */
public interface SignPort {

    /** 注册凭据：重复注册覆盖 */
    void registerKey(String accessKey, String secretKey);

    boolean hasKey(String accessKey);

    /** 规范请求构造 */
    String canonical(String method, String path, Map<String, String> query,
                     Map<String, List<String>> headers, String payloadHash);

    /** string-to-sign 构造 */
    String stringToSign(String timestamp, String scope, String canonicalRequest);

    /** 派生签名（凭据未注册拒绝） */
    String sign(String accessKey, String date, String region, String service, String stringToSign);

    /** presigned 核验：参数/过期/签名三关 */
    void verifyPresigned(String accessKey, Map<String, String> params,
                         long serverNowMs, long toleranceMs);

    /** 时钟偏斜核验 */
    void checkSkew(long requestMs, long serverNowMs, long toleranceMs);

    /** 分块签名链起步 */
    ChunkChain startChunk(String seedSignature);

    /** vaultkernel 密钥串形态只读联动：凭据掩码形状（形状数据不 import vaultkernel） */
    String keyShape(String accessKey);

    static SignPort inMemory() {
        return new SignHub();
    }
}
