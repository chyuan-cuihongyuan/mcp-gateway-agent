package cn.chyuan.ai.domain.configcenter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置摘要计算器（工单 0255 AG5，借鉴 Nacos MD5 摘要长轮询）—
 * 命名空间配置集 → MD5 摘要（键序无关：按键排序后逐行 key=md5(content) 拼接再 MD5）。
 * 客户端与网关双侧可独立计算，摘要一致即配置未变。
 *
 * @author chyuan
 */
public final class ConfigDigestCalculator {

    private ConfigDigestCalculator() {
    }

    /** 配置集摘要（key → content；空集返回空串摘要的 md5） */
    public static String digest(Map<String, String> keyContents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(keyContents).entrySet()) {
            sb.append(entry.getKey()).append('=').append(md5(entry.getValue())).append('\n');
        }
        return md5(sb.toString());
    }

    /** 单内容 md5 十六进制 */
    public static String md5(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
