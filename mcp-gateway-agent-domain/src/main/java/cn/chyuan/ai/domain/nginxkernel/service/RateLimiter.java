package cn.chyuan.ai.domain.nginxkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 限速器（工单 0928 DE6，nginx limit_req 思想）。
 * 速率令牌桶（rate 个/秒）与 burst 突发容量/超限拒绝/时间推进补充。
 */
public final class RateLimiter {

    private final double ratePerSecond;
    private final double burst;
    private double tokens;
    private long nowMs = 0;

    public RateLimiter(double ratePerSecond, int burst) {
        if (ratePerSecond <= 0 || burst < 0) {
            throw new IllegalArgumentException("限速参数非法");
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    /** 时间推进：按毫秒补充令牌 */
    public void advance(long millis) {
        nowMs += millis;
        tokens = Math.min(burst, tokens + ratePerSecond * millis / 1000.0);
    }

    /** 尝试消费一个令牌：可用返回 true */
    public boolean allow() {
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    public double tokens() {
        return tokens;
    }
}
