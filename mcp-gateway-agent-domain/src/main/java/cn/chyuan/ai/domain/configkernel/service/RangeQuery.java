package cn.chyuan.ai.domain.configkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 前缀范围查询（工单 0470 BD7，etcd range 思想）。
 * 键序字典序范围 scan（[from, to)）/limit 分页（nextCursor 续扫）/
 * from 游标/前缀计数。只读不分配 revision。
 */
public class RangeQuery {

    /** 范围条目 */
    public record Kv(String key, String value) {
    }

    /** 分页结果：本页条目 + 下一页游标（独占起点，null 表示无更多） */
    public record Page(List<Kv> items, String nextCursor, boolean hasMore) {
    }

    /** 范围扫描：[fromKey, toKey)，limit > 0；toKey 为 null 表示无上界 */
    public Page scan(MvccStore store, String fromKey, String toKey, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须 > 0: " + limit);
        }
        List<Kv> items = new ArrayList<>();
        String cursor = null;
        boolean hasMore = false;
        for (String key : store.keys()) {
            if (key.compareTo(fromKey) < 0) {
                continue;
            }
            if (toKey != null && key.compareTo(toKey) >= 0) {
                break;
            }
            if (items.size() == limit) {
                hasMore = true;
                cursor = items.get(items.size() - 1).key();
                break;
            }
            MvccStore.HistoryResult result = store.get(key);
            if (result.present()) {
                items.add(new Kv(key, result.value()));
            }
        }
        return new Page(List.copyOf(items), hasMore ? cursor : null, hasMore);
    }

    /** 游标续扫：从上次返回的 nextCursor（独占）继续 */
    public Page scanAfter(MvccStore store, String cursor, String toKey, int limit) {
        if (cursor == null) {
            throw new IllegalArgumentException("游标为空，请直接 scan");
        }
        return scan(store, successor(cursor), toKey, limit);
    }

    /** 前缀计数（只计存活键） */
    public int prefixCount(MvccStore store, String prefix) {
        int count = 0;
        for (String key : store.keys()) {
            if (key.startsWith(prefix) && store.get(key).present()) {
                count++;
            }
        }
        return count;
    }

    /** 游标后继：key + '\0' 保证字典序严格大于原键 */
    private String successor(String key) {
        return key + '\0';
    }
}
