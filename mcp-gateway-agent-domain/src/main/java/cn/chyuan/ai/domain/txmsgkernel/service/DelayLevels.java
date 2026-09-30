package cn.chyuan.ai.domain.txmsgkernel.service;

/**
 * 延迟等级（工单 1140 EX6，rocketmq 思想）。
 * 18 级延迟表（1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h）；
 * 到期前不投递；虚拟时钟到期推进投递；非法等级拒绝。
 */
public final class DelayLevels {

    /** 各级延迟秒数（1 tick = 1 秒虚拟口径） */
    public static final int[] LEVEL_SECONDS = {
            1, 5, 10, 30, 60, 120, 180, 240, 300, 600, 1200, 1800, 3600, 7200,
            43200, 86400, 172800, 259200
    };

    private DelayLevels() {
    }

    public static int levelCount() {
        return LEVEL_SECONDS.length;
    }

    /** 合法性校验：1..18 */
    public static void validate(int level) {
        if (level < 1 || level > LEVEL_SECONDS.length) {
            throw new IllegalArgumentException("非法延迟等级: " + level);
        }
    }

    /** 投递时刻 = 诞生 tick + 等级延迟 */
    public static long deliverAt(int level, long bornTick) {
        validate(level);
        return bornTick + LEVEL_SECONDS[level - 1];
    }

    /** 到期判定 */
    public static boolean due(int level, long bornTick, long nowTick) {
        return nowTick >= deliverAt(level, bornTick);
    }
}
