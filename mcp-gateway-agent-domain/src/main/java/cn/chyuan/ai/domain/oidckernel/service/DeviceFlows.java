package cn.chyuan.ai.domain.oidckernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * device flow（工单 1102 ET5，keycloak 思想）。
 * 设备码+用户码颁发/未确认轮询 pending/slow_down 节流（轮询快于间隔）/确认后设备码互换令牌。
 */
public final class DeviceFlows {

    private static final class Grant {
        final String deviceCode;
        final String userCode;
        final long intervalTicks;
        long lastPollAt = Long.MIN_VALUE;
        String confirmedSubject;
        String scope = "";

        Grant(String deviceCode, String userCode, long intervalTicks) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.intervalTicks = intervalTicks;
        }
    }

    private final Map<String, Grant> byDeviceCode = new HashMap<>();
    private final Map<String, Grant> byUserCode = new HashMap<>();
    private long seq;

    /** 发起：返回 设备码|用户码 */
    public String begin(long intervalTicks) {
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("轮询间隔必须为正: " + intervalTicks);
        }
        Grant grant = new Grant("DC-" + (++seq), "UC-" + String.format("%04d", seq), intervalTicks);
        byDeviceCode.put(grant.deviceCode, grant);
        byUserCode.put(grant.userCode, grant);
        return grant.deviceCode + "|" + grant.userCode;
    }

    /** 用户确认（授权主体绑定） */
    public void confirm(String userCode, String subject, String scope) {
        Grant grant = byUserCode.get(userCode);
        if (grant == null) {
            throw new IllegalArgumentException("未知用户码拒绝: " + userCode);
        }
        grant.confirmedSubject = subject;
        grant.scope = scope == null ? "" : scope;
    }

    /** 设备轮询：PENDING / SLOW_DOWN（快于间隔节流）/ TOKEN:subject|scope 确认后互换 */
    public String poll(String deviceCode, long nowTick) {
        Grant grant = byDeviceCode.get(deviceCode);
        if (grant == null) {
            throw new IllegalArgumentException("未知设备码拒绝: " + deviceCode);
        }
        if (grant.lastPollAt != Long.MIN_VALUE && nowTick - grant.lastPollAt < grant.intervalTicks) {
            grant.lastPollAt = nowTick;
            return "SLOW_DOWN";
        }
        grant.lastPollAt = nowTick;
        if (grant.confirmedSubject == null) {
            return "PENDING";
        }
        return "TOKEN:" + grant.confirmedSubject + "|" + grant.scope;
    }

    public boolean confirmed(String userCode) {
        Grant grant = byUserCode.get(userCode);
        return grant != null && grant.confirmedSubject != null;
    }
}
