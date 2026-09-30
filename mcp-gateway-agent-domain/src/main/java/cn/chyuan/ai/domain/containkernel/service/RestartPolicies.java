package cn.chyuan.ai.domain.containkernel.service;

/**
 * restart 策略（工单 1172 FB6，moby 思想）。
 * no 不重启/always 总重启/on-failure 限次数/退避倍增上限 60/成功重置计数。
 */
public final class RestartPolicies {

    /** 策略：类型 + on-failure 最大次数 */
    public record Policy(String type, int maxRetry) {

        public Policy {
            if (!"no".equals(type) && !"always".equals(type) && !"on-failure".equals(type)) {
                throw new IllegalArgumentException("未知 restart 策略: " + type);
            }
            if ("on-failure".equals(type) && maxRetry < 0) {
                throw new IllegalArgumentException("on-failure 最大次数不能为负");
            }
        }
    }

    /** 退避上限 ticks */
    public static final long BACKOFF_CAP = 60;

    private final Policy policy;
    private int attempts;
    private int restarts;

    public RestartPolicies(Policy policy) {
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    public int attempts() {
        return attempts;
    }

    public int restarts() {
        return restarts;
    }

    /** 异常退出处置：返回是否自动重启（always 总重启；on-failure 未达上限重启；no 不重启） */
    public boolean onCrash() {
        return switch (policy.type()) {
            case "always" -> {
                attempts++;
                restarts++;
                yield true;
            }
            case "on-failure" -> {
                if (attempts < policy.maxRetry()) {
                    attempts++;
                    restarts++;
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    /** 正常启动成功重置连续失败计数 */
    public void onCleanStart() {
        attempts = 0;
    }

    /** 下次退避 ticks：1 起倍增，封顶 60 */
    public long nextBackoff() {
        long backoff = 1;
        for (int i = 0; i < attempts - 1 && backoff < BACKOFF_CAP; i++) {
            backoff = Math.min(backoff * 2, BACKOFF_CAP);
        }
        return backoff;
    }
}
