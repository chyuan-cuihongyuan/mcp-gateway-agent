package cn.chyuan.ai.domain.llmchannel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渠道 fallback 链纯函数策略（工单 0155）
 *
 * <p>渠道表 fallback_channel_id 自引用形成单向链（A→B→C）。
 * 本类只做图上的纯计算，不触达仓储：
 * ①保存前防环校验——新增边 self→target 后链上不允许回到 self（A→B→A 拒绝保存）；
 * ②运行期降级链解析——从起点沿链走至多 maxHops 跳，天然防环防自环防重复。
 *
 * <p>口径：链长上限由调用方给定（网关当前取 2）；边缺失（无 fallback）即链尾。
 *
 * @author chyuan
 */
public final class FallbackChainPolicy {

    /** fallback 链跳数上限（工单 0155：链长上限 2） */
    public static final int MAX_HOPS = 2;

    private FallbackChainPolicy() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 保存防环校验：若把 self 的 fallback 指到 target（self→target），沿 target 的既有链
     * 走有限步（步数受边数上限约束，任何图规模都有界），回到 self 即成环。
     *
     * @param fallbackEdges 既有渠道 id → fallback 渠道 id（可含旧 self 边，校验时被新边覆盖）
     * @param selfId        被保存渠道自身 id
     * @param targetId      意图指向的 fallback 渠道 id
     * @return true=成环（拒绝保存）；false=安全
     */
    public static boolean createsCycle(Map<Long, Long> fallbackEdges, long selfId, long targetId) {
        if (fallbackEdges == null) {
            return false;
        }
        // 自环即环
        if (selfId == targetId) {
            return true;
        }
        Long current = targetId;
        int bound = fallbackEdges.size() + 1;
        for (int i = 0; i < bound && current != null; i++) {
            if (current == selfId) {
                return true;
            }
            current = fallbackEdges.get(current);
        }
        return false;
    }

    /**
     * 运行期降级链解析：从 start 沿链依次给出 fallback 渠道 id，至多 maxHops 跳；
     * 环/自环/重复访问即截断（运行期兜底，历史脏数据不致死循环）。
     *
     * @param fallbackEdges 渠道 id → fallback 渠道 id
     * @param startId       主链耗尽后最后一个渠道 id
     * @param maxHops       链长上限（网关当前 {@link #MAX_HOPS}）
     * @return 有序 fallback 渠道 id（可能为空）
     */
    public static List<Long> resolveChain(Map<Long, Long> fallbackEdges, long startId, int maxHops) {
        List<Long> chain = new ArrayList<>();
        if (fallbackEdges == null || fallbackEdges.isEmpty() || maxHops <= 0) {
            return chain;
        }
        Long current = fallbackEdges.get(startId);
        for (int hop = 0; hop < maxHops && current != null; hop++) {
            long id = current;
            if (id == startId || chain.contains(id)) {
                break;
            }
            chain.add(id);
            current = fallbackEdges.get(id);
        }
        return chain;
    }
}
