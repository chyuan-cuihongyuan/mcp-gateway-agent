package cn.chyuan.ai.types.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * IP/CIDR 白名单匹配工具（工单 0045，密钥生命周期）
 *
 * <p>支持条目形态：单 IP（192.168.1.10）与 CIDR 网段（10.0.0.0/8）。
 * IPv6 仅支持单地址全等匹配；网段条目仅支持 IPv4。
 * 列表为空或 null 视为不限制（放行）；畸形条目不匹配（该条目失效，fail-closed 倾向）。
 *
 * @author chyuan
 */
public final class IpCidrUtil {

    private IpCidrUtil() {
    }

    /**
     * @return true=放行（列表为空/未配置，或 clientIp 命中任一条目）
     */
    public static boolean allows(List<String> allowList, String clientIp) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        boolean hasValidEntry = false;
        for (String entry : allowList) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            hasValidEntry = true;
        }
        if (!hasValidEntry) {
            // 全空白条目 = 实际未配置白名单（与空列表同语义：不限制）
            return true;
        }
        if (clientIp == null || clientIp.isBlank()) {
            // 配了白名单但取不到来源 IP：拒绝（fail-closed）
            return false;
        }
        for (String entry : allowList) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (matchesEntry(entry.trim(), clientIp.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEntry(String entry, String clientIp) {
        int slash = entry.indexOf('/');
        if (slash < 0) {
            return entry.equals(clientIp);
        }
        try {
            byte[] network = InetAddress.getByName(entry.substring(0, slash)).getAddress();
            int prefix = Integer.parseInt(entry.substring(slash + 1));
            byte[] client = InetAddress.getByName(clientIp).getAddress();
            if (network.length != client.length || prefix < 0 || prefix > network.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != client[i]) {
                    return false;
                }
            }
            if (remBits > 0 && fullBytes < network.length) {
                int mask = 0xFF << (8 - remBits);
                if ((network[fullBytes] & mask) != (client[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
