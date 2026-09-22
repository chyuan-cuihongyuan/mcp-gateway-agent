package cn.chyuan.ai.domain.vaultkernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥端口+组合管线（工单 0572 BP8）。
 * VaultPort（内存密钥库：创建密钥-加密-解密-签名-验签）+组合管线
 * （创建→轮换→信封加密→签名→解密→验签闭环）+与一期 virtual key 登记
 * 只读联动/vault-kernel.enabled 默认关（JDK JCA 零新依赖）。
 */
public interface VaultPort {

    /** 数据密钥材料（明文 DEK 交数据侧即刻使用 + 持久化信封） */
    record DataKey(byte[] plaintextDek, KeyRing.Envelope envelope) {
    }

    /** 创建命名密钥环 */
    void createKey(String keyId);

    /** 轮换并返回新版本号 */
    int rotate(String keyId);

    /** 生成数据密钥（信封用当前版本包裹） */
    DataKey generateDataKey(String keyId);

    /** 解包数据密钥（按信封版本） */
    byte[] unwrapDataKey(String keyId, KeyRing.Envelope envelope);

    /** 重包裹到当前版本 */
    KeyRing.Envelope rewrap(String keyId, KeyRing.Envelope envelope);

    /** 签名与验签 */
    HmacService.Signature sign(String keyId, byte[] data);

    boolean verify(String keyId, byte[] data, HmacService.Signature signature);

    /** virtual key 只读联动登记（vk → keyId 映射，只记录不改动一期既有类） */
    void bindVirtualKey(String virtualKeyId, String keyId);

    String resolveVirtualKey(String virtualKeyId);

    /** 内存假实现 */
    class InMemoryVault implements VaultPort {

        private final Map<String, KeyRing> rings = new LinkedHashMap<>();
        private final Map<String, String> virtualKeyBindings = new LinkedHashMap<>();
        private final Map<String, HmacService> hmacs = new LinkedHashMap<>();

        private KeyRing ring(String keyId) {
            KeyRing ring = rings.get(keyId);
            if (ring == null) {
                throw new IllegalArgumentException("密钥环不存在: " + keyId);
            }
            return ring;
        }

        @Override
        public void createKey(String keyId) {
            if (rings.containsKey(keyId)) {
                throw new IllegalArgumentException("密钥环已存在: " + keyId);
            }
            KeyRing ring = new KeyRing(keyId);
            rings.put(keyId, ring);
            hmacs.put(keyId, new HmacService(ring));
        }

        @Override
        public int rotate(String keyId) {
            return ring(keyId).rotate();
        }

        @Override
        public DataKey generateDataKey(String keyId) {
            KeyRing.Generated generated = ring(keyId).generateDataKey();
            return new DataKey(generated.plaintextDek(), generated.envelope());
        }

        @Override
        public byte[] unwrapDataKey(String keyId, KeyRing.Envelope envelope) {
            return ring(keyId).unwrapDataKey(envelope);
        }

        @Override
        public KeyRing.Envelope rewrap(String keyId, KeyRing.Envelope envelope) {
            return ring(keyId).rewrap(envelope, ring(keyId).currentVersion());
        }

        @Override
        public HmacService.Signature sign(String keyId, byte[] data) {
            return hmacs.get(keyId).sign(data);
        }

        @Override
        public boolean verify(String keyId, byte[] data, HmacService.Signature signature) {
            return hmacs.get(keyId).verify(data, signature);
        }

        @Override
        public void bindVirtualKey(String virtualKeyId, String keyId) {
            if (!rings.containsKey(keyId)) {
                throw new IllegalArgumentException("绑定目标密钥环不存在: " + keyId);
            }
            virtualKeyBindings.put(virtualKeyId, keyId);
        }

        @Override
        public String resolveVirtualKey(String virtualKeyId) {
            String keyId = virtualKeyBindings.get(virtualKeyId);
            if (keyId == null) {
                throw new IllegalArgumentException("virtual key 未绑定: " + virtualKeyId);
            }
            return keyId;
        }

        public List<String> keyIds() {
            return List.copyOf(rings.keySet());
        }
    }
}
