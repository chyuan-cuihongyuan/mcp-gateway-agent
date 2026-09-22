package cn.chyuan.ai.domain.vaultkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量与配额（工单 0571 BP7，vault batch 思想）。
 * 批量加解密清单逐项处理（部分失败逐项报告，成功项不受影响）/
 * 调用配额计数/超限拒绝并保留计数/配额重置。
 */
public final class BatchQuota {

    /** 批量单项结果 */
    public record Item<T>(int index, T value, String error) {
        public boolean success() {
            return error == null;
        }
    }

    /** 配额（总量限流；超限拒绝但保留已用计数） */
    public static final class Quota {
        private final long limit;
        private long used;

        public Quota(long limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("配额上限须≥1");
            }
            this.limit = limit;
        }

        /** 消耗 n；超限拒绝并保留计数 */
        public boolean consume(long n) {
            if (n <= 0) {
                throw new IllegalArgumentException("消耗量须为正");
            }
            if (used + n > limit) {
                return false;
            }
            used += n;
            return true;
        }

        public long remaining() {
            return limit - used;
        }

        public long used() {
            return used;
        }

        public void reset() {
            used = 0;
        }
    }

    private BatchQuota() {
    }

    /** 批量映射：逐项 try，失败项携带错误信息 */
    public static <I, O> List<Item<O>> mapItems(List<I> inputs, java.util.function.Function<I, O> fn) {
        List<Item<O>> out = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            try {
                out.add(new Item<>(i, fn.apply(inputs.get(i)), null));
            } catch (IllegalArgumentException e) {
                out.add(new Item<>(i, null, e.getMessage()));
            }
        }
        return out;
    }

    /** 批量结果统计 */
    public static <T> String summarize(List<Item<T>> items) {
        long ok = items.stream().filter(Item::success).count();
        return "ok=" + ok + ",failed=" + (items.size() - ok);
    }
}
