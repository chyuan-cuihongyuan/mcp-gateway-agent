package cn.chyuan.ai.domain.vaultkernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥端口组合管线 BP8 单测（工单 0572）：
 * 创建→轮换→生成数据密钥→签名→解包→验签闭环 + virtual key 只读绑定联动。
 */
class VaultPortPipelineTest {

    @Test
    void BP8_组合管线_创建轮换信封签名验签闭环() {
        VaultPort.InMemoryVault vault = new VaultPort.InMemoryVault();
        vault.createKey("tenant-a");
        assertEquals(2, vault.rotate("tenant-a"), "轮换至 v2");
        // 生成数据密钥（v2 信封）并用 DEK 加密数据（数据侧自持 AES-GCM）
        VaultPort.DataKey dataKey = vault.generateDataKey("tenant-a");
        assertEquals(2, dataKey.envelope().version());
        byte[] data = "order-42".getBytes(StandardCharsets.UTF_8);
        javax.crypto.spec.SecretKeySpec dek = EnvelopeCrypto.kek(dataKey.plaintextDek());
        byte[] sealed = EnvelopeCrypto.wrap(data, dek);
        // 签名（v2）
        HmacService.Signature signature = vault.sign("tenant-a", sealed);
        assertEquals(2, signature.version());
        // 解包 DEK 还原数据
        byte[] unwrappedDek = vault.unwrapDataKey("tenant-a", dataKey.envelope());
        assertTrue(Arrays.equals(dataKey.plaintextDek(), unwrappedDek), "信封解包 DEK 等价");
        assertTrue(Arrays.equals(data, EnvelopeCrypto.unwrap(sealed, EnvelopeCrypto.kek(unwrappedDek))),
                "解密还原数据");
        // 验签
        assertTrue(vault.verify("tenant-a", sealed, signature));
        assertFalse(vault.verify("tenant-a", "order-43".getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void BP8_重包裹到当前版本与未知密钥环拒绝() {
        VaultPort.InMemoryVault vault = new VaultPort.InMemoryVault();
        vault.createKey("tenant-b");
        VaultPort.DataKey dataKey = vault.generateDataKey("tenant-b");
        vault.rotate("tenant-b");
        KeyRing.Envelope rewrapped = vault.rewrap("tenant-b", dataKey.envelope());
        assertEquals(2, rewrapped.version(), "重包裹到当前版本");
        assertTrue(Arrays.equals(dataKey.plaintextDek(), vault.unwrapDataKey("tenant-b", rewrapped)),
                "重包裹后 DEK 可解等价");
        assertThrows(IllegalArgumentException.class, () -> vault.generateDataKey("ghost"),
                "未知密钥环拒绝");
        assertThrows(IllegalArgumentException.class, () -> vault.createKey("tenant-b"),
                "重复创建拒绝");
    }

    @Test
    void BP8_virtualKey只读绑定联动() {
        VaultPort.InMemoryVault vault = new VaultPort.InMemoryVault();
        vault.createKey("vk-target");
        vault.bindVirtualKey("vk-001", "vk-target");
        assertEquals("vk-target", vault.resolveVirtualKey("vk-001"), "virtual key 只读解析");
        assertThrows(IllegalArgumentException.class, () -> vault.bindVirtualKey("vk-002", "ghost"),
                "绑定目标不存在拒绝");
        assertThrows(IllegalArgumentException.class, () -> vault.resolveVirtualKey("vk-404"),
                "未绑定解析拒绝");
        assertEquals(java.util.List.of("vk-target"), vault.keyIds());
    }
}
