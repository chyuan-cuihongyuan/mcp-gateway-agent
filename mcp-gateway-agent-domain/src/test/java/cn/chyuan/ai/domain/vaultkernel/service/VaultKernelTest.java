package cn.chyuan.ai.domain.vaultkernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥治理内核 BP1-BP7 单测（工单 0565-0571）：
 * 信封加密/版本轮换/重包裹/HMAC 恒时验证/HKDF 派生/动态租约/批量配额。
 */
class VaultKernelTest {

    @Test
    void BP1_信封加密DEK生成与篡改拒绝() {
        byte[] dek = EnvelopeCrypto.generateDek(32);
        assertEquals(32, dek.length);
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCrypto.generateDek(8), "DEK 长度约束");
        javax.crypto.spec.SecretKeySpec kek = EnvelopeCrypto.kek(new byte[32]);
        byte[] wrapped = EnvelopeCrypto.wrap(dek, kek);
        assertEquals(32 + 12 + 16, wrapped.length, "包裹=DEK+IV12+tag16");
        assertTrue(Arrays.equals(dek, EnvelopeCrypto.unwrap(wrapped, kek)), "解包还原往返");
        byte[] tampered = wrapped.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCrypto.unwrap(tampered, kek),
                "密文篡改 AEAD 拒绝");
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCrypto.kek(new byte[8]), "KEK 长度约束");
    }

    @Test
    void BP2_密钥版本轮换与信封版本回退() {
        KeyRing ring = new KeyRing("orders-key");
        assertEquals(1, ring.currentVersion(), "创建即 v1");
        KeyRing.Generated first = ring.generateDataKey();
        assertEquals(1, first.envelope().version(), "加密恒用最新版本");
        int v2 = ring.rotate();
        assertEquals(2, v2);
        KeyRing.Generated second = ring.generateDataKey();
        assertEquals(2, second.envelope().version());
        assertTrue(Arrays.equals(first.plaintextDek(), ring.unwrapDataKey(first.envelope())),
                "旧版本信封仍可解（版本回退）");
        assertNotEquals(Arrays.toString(first.plaintextDek()), Arrays.toString(second.plaintextDek()));
        assertEquals(List.of("ROTATE v1", "ROTATE v2"), ring.events(), "轮换事件有序");
        assertThrows(IllegalArgumentException.class, () -> ring.key(9), "未注册版本拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> ring.unwrapDataKey(new KeyRing.Envelope(9, new byte[40])), "信封版本未注册拒绝");
    }

    @Test
    void BP3_批量重包裹不落业务明文() {
        KeyRing ring = new KeyRing("rewrap-key");
        KeyRing.Envelope envelope = ring.generateDataKey().envelope();
        ring.rotate();
        KeyRing.Envelope rewrapped = ring.rewrap(envelope, ring.currentVersion());
        assertEquals(2, rewrapped.version(), "重包裹到当前版本");
        assertTrue(Arrays.equals(ring.unwrapDataKey(envelope), ring.unwrapDataKey(rewrapped)),
                "重包裹前后 DEK 等价（业务明文未参与）");
        RewrapService service = new RewrapService(ring);
        KeyRing.Envelope bad = new KeyRing.Envelope(1, new byte[41]);
        List<KeyRing.Envelope> batch = List.of(
                ring.generateDataKey().envelope(),
                bad,
                ring.generateDataKey().envelope());
        RewrapService.BatchResult result = service.rewrapBatch(batch);
        assertEquals(2, result.successCount());
        assertEquals(1, result.failureCount());
        assertFalse(result.items().get(1).success(), "失败项逐项报告");
        assertTrue(result.items().get(0).success(), "成功项不受失败项影响");
        assertTrue(service.idempotent(envelope), "重包裹往返幂等");
        assertThrows(IllegalArgumentException.class, () -> ring.rewrap(envelope, 9), "目标版本未注册拒绝");
    }

    @Test
    void BP4_HMAC签名恒时验证() {
        KeyRing ring = new KeyRing("hmac-key");
        HmacService hmac = new HmacService(ring);
        byte[] payload = "audit-log".getBytes(StandardCharsets.UTF_8);
        HmacService.Signature signature = hmac.sign(payload);
        assertEquals(1, signature.version(), "签名绑定当前版本");
        assertTrue(hmac.verify(payload, signature), "验签通过");
        assertFalse(hmac.verify("tampered".getBytes(StandardCharsets.UTF_8), signature), "篡改载荷拒绝");
        ring.rotate();
        assertTrue(hmac.verify(payload, signature), "轮换后旧版签名按信封版本密钥仍可验证（版本链保留）");
        assertEquals(1, signature.version());
        assertNotEquals(signature, hmac.sign(payload), "新版本签名版本号前移");
        assertTrue(hmac.verify(payload, hmac.sign(payload)));
        byte[] encoded = hmac.encode(signature);
        HmacService.Signature decoded = hmac.decode(encoded);
        assertEquals(signature.version(), decoded.version());
        assertTrue(Arrays.equals(signature.mac(), decoded.mac()), "信封编解码 mac 往返");
        assertThrows(IllegalArgumentException.class, () -> hmac.decode(new byte[8]), "信封格式非法拒绝");
        HmacService.Signature ghost = new HmacService.Signature(9, signature.mac());
        assertThrows(IllegalArgumentException.class, () -> hmac.verify(payload, ghost), "密钥未注册拒绝");
    }

    @Test
    void BP5_HKDF派生用途隔离() {
        byte[] ikm = EnvelopeCrypto.generateDek(32);
        byte[] enc = HkdfDerive.derive(ikm, new byte[32], "encrypt".getBytes(StandardCharsets.UTF_8), 32);
        byte[] mac = HkdfDerive.derive(ikm, new byte[32], "sign".getBytes(StandardCharsets.UTF_8), 32);
        assertFalse(Arrays.equals(enc, mac), "同根不同用途子密钥不同");
        byte[] again = HkdfDerive.derive(ikm, new byte[32], "encrypt".getBytes(StandardCharsets.UTF_8), 32);
        assertTrue(Arrays.equals(enc, again), "同参数派生确定性");
        byte[] encSalted = HkdfDerive.derive(ikm, EnvelopeCrypto.generateDek(32),
                "encrypt".getBytes(StandardCharsets.UTF_8), 32);
        assertFalse(Arrays.equals(enc, encSalted), "不同盐派生不同");
        byte[] longOut = HkdfDerive.expand(HkdfDerive.extract(new byte[32], ikm), new byte[0], 64);
        assertEquals(64, longOut.length, "多块 Expand 长度正确");
        assertThrows(IllegalArgumentException.class,
                () -> HkdfDerive.expand(new byte[32], new byte[0], 255 * 32 + 1), "输出长度上界拒绝");
        assertThrows(IllegalArgumentException.class, () -> HkdfDerive.expand(new byte[32], new byte[0], 0),
                "零长度拒绝");
    }

    @Test
    void BP6_动态密钥租约() {
        long[] now = {1000L};
        DynamicKeyLease leases = new DynamicKeyLease(() -> now[0]);
        DynamicKeyLease.Lease lease = leases.issue("batch-export", 5_000L);
        assertTrue(lease.expiresAt() == 6_000L);
        assertTrue(leases.usable(lease.id()));
        DynamicKeyLease.Lease renewed = leases.renew(lease.id(), 5_000L);
        assertEquals(11_000L, renewed.expiresAt(), "续租延长到期");
        now[0] = 12_000L;
        assertFalse(leases.usable(lease.id()), "到期自动过期拒绝");
        assertThrows(IllegalArgumentException.class, () -> leases.renew(lease.id(), 1_000L),
                "过期租约续租拒绝");
        DynamicKeyLease.Lease alive = leases.issue("backup", 60_000L);
        leases.revoke(alive.id());
        assertFalse(leases.usable(alive.id()), "撤销后使用拒绝");
        leases.revoke(alive.id());
        leases.issue("still-active", 60_000L);
        assertEquals(1, leases.activeLeases().size(), "撤销与过期不进活跃清单");
        assertThrows(IllegalArgumentException.class, () -> leases.issue("x", 0L), "零 TTL 拒绝");
    }

    @Test
    void BP7_批量部分失败与配额() {
        KeyRing ring = new KeyRing("batch-key");
        List<byte[]> plaintexts = java.util.Arrays.asList(
                "a".getBytes(), null, "c".getBytes(StandardCharsets.UTF_8));
        List<BatchQuota.Item<byte[]>> items = BatchQuota.mapItems(plaintexts, data -> {
            if (data == null) {
                throw new IllegalArgumentException("空明文拒绝");
            }
            KeyRing.Envelope e = ring.generateDataKey().envelope();
            byte[] sealed = EnvelopeCrypto.wrap(data, ring.key(ring.currentVersion()));
            return sealed;
        });
        assertEquals("ok=2,failed=1", BatchQuota.summarize(items));
        assertFalse(items.get(1).success());
        assertTrue(items.get(0).success());
        BatchQuota.Quota quota = new BatchQuota.Quota(10);
        assertTrue(quota.consume(6));
        assertFalse(quota.consume(6), "超限拒绝");
        assertEquals(6, quota.used(), "超限保留已用计数");
        assertEquals(4, quota.remaining());
        quota.reset();
        assertEquals(10, quota.remaining());
        assertThrows(IllegalArgumentException.class, () -> new BatchQuota.Quota(0), "零配额拒绝");
        assertThrows(IllegalArgumentException.class, () -> quota.consume(0), "零消耗拒绝");
    }
}
