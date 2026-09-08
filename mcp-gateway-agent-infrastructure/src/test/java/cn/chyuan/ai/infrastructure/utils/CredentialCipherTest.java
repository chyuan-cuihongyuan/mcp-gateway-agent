package cn.chyuan.ai.infrastructure.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭证加密器测试（工单 0062：往返/前缀探测/存量明文/无密钥降级/篡改）
 */
@DisplayName("凭证加密器测试")
public class CredentialCipherTest {

    @Test
    @DisplayName("往返 — 加密带前缀、解密还原；同明文两次密文不同（随机 IV）")
    public void testRoundTrip() {
        CredentialCipher cipher = new CredentialCipher("test-key-123");
        String plain = "vk-secret-credential";

        String encrypted = cipher.encrypt(plain);
        assertTrue(CredentialCipher.isEncrypted(encrypted));
        assertNotEquals(plain, encrypted);
        assertNotEquals(encrypted, cipher.encrypt(plain), "随机 IV 同明文密文不同");
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("存量明文兼容 — 明文原样直通（读侧不解、写侧加密）")
    public void testLegacyPlaintextPassthrough() {
        CredentialCipher cipher = new CredentialCipher("k");
        assertEquals("legacy-plain", cipher.decrypt("legacy-plain"));
        assertFalse(CredentialCipher.isEncrypted("legacy-plain"));
    }

    @Test
    @DisplayName("无密钥降级 — 明文读写直通、isEnabled=false")
    public void testNoKeyDegradesToPlaintext() {
        CredentialCipher cipher = new CredentialCipher("");
        assertFalse(cipher.isEnabled());
        assertEquals("plain", cipher.encrypt("plain"));
        assertEquals("plain", cipher.decrypt("plain"));
    }

    @Test
    @DisplayName("密文无密钥 — 返回空串不外抛；密钥不符解密失败同样空串")
    public void testCiphertextWithoutKeyReturnsEmpty() {
        CredentialCipher withKey = new CredentialCipher("right-key");
        String encrypted = withKey.encrypt("secret");

        CredentialCipher noKey = new CredentialCipher("");
        assertEquals("", noKey.decrypt(encrypted));

        CredentialCipher wrongKey = new CredentialCipher("wrong-key");
        assertEquals("", wrongKey.decrypt(encrypted));
    }

    @Test
    @DisplayName("空值/已加密值 — encrypt 幂等直通")
    public void testEncryptIdempotent() {
        CredentialCipher cipher = new CredentialCipher("k");
        assertNull(cipher.encrypt(null));
        assertEquals("", cipher.encrypt(""));
        String encrypted = cipher.encrypt("v");
        assertEquals(encrypted, cipher.encrypt(encrypted), "已加密值直通");
    }
}
