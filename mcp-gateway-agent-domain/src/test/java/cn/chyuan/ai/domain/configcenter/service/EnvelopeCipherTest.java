package cn.chyuan.ai.domain.configcenter.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信封加密单测（工单 0254 AG4）：加解密 roundtrip/篡改检测/口令敏感/格式标记。
 */
class EnvelopeCipherTest {

    @Test
    void 加解密往返与密文格式() {
        EnvelopeCipher cipher = new EnvelopeCipher(() -> "kek-secret");
        String token = cipher.encrypt("{\"apikey\":\"sk-123\"}");
        assertTrue(EnvelopeCipher.isToken(token));
        assertTrue(token.startsWith(EnvelopeCipher.TOKEN_PREFIX));
        assertEquals(3, token.substring(EnvelopeCipher.TOKEN_PREFIX.length()).split(":").length);
        assertEquals("{\"apikey\":\"sk-123\"}", cipher.decrypt(token));
        // 同明文两次加密密文不同（随机 IV + 随机 DEK）
        assertNotEquals(token, cipher.encrypt("{\"apikey\":\"sk-123\"}"));
    }

    @Test
    void 篡改检测() {
        EnvelopeCipher cipher = new EnvelopeCipher(() -> "kek-secret");
        String token = cipher.encrypt("sensitive");
        String[] parts = token.substring(EnvelopeCipher.TOKEN_PREFIX.length()).split(":");
        String tamperedCt = EnvelopeCipher.TOKEN_PREFIX + parts[0] + ":" + parts[1] + ":"
                + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(tamperedCt));
        // 口令不符同样拒绝
        EnvelopeCipher other = new EnvelopeCipher(() -> "wrong-kek");
        assertThrows(IllegalArgumentException.class, () -> other.decrypt(token));
        // 非法格式
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("plain"));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("enc-v1:a:b"));
    }

    @Test
    void 空口令拒绝与工具方法() {
        assertThrows(IllegalArgumentException.class, () -> new EnvelopeCipher(() -> " "));
        assertThrows(IllegalArgumentException.class, () -> new EnvelopeCipher(null));
        assertFalse(EnvelopeCipher.isToken(null));
        assertEquals(64, EnvelopeCipher.sha256Hex("x").length());
        assertEquals(EnvelopeCipher.sha256Hex("x"), EnvelopeCipher.sha256Hex("x"));
    }
}
