package cn.chyuan.ai.domain.policy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 策略决策日志（工单 0265 AH5，借鉴 OPA decision logs）—
 * 每次评估落一条：输入摘要（脱敏：subject/object/action 截断 + 环境键白名单外不落）、
 * 命中策略、结论、缓存命中标记、耗时。端口存储（内存缺省 + MyBatis policy_decision_log）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class DecisionLogRecorder {

    /** 输入摘要最大长度（脱敏截断） */
    public static final int MAX_SUMMARY_LEN = 128;

    /** 决策日志条目 */
    public record DecisionLog(long id, long atMs, String subject, String object, String action,
            String decision, List<String> hitStatementNames, boolean cached, long costMs) {
    }

    /** 日志存储端口（infrastructure 经 MyBatis 落 policy_decision_log 表） */
    public interface DecisionLogStore {

        void append(DecisionLog entry);

        /** 查询（过滤参数可空；分页 offset/limit，limit 封顶由实现保证） */
        List<DecisionLog> query(String decision, Long fromMs, Long toMs, int offset, int limit);
    }

    /** 内存缺省存储（环形 1000 条，limit 封顶 200） */
    public static class InMemoryDecisionLogStore implements DecisionLogStore {

        private final Deque<DecisionLog> entries = new ArrayDeque<>();
        private static final int MAX_ENTRIES = 1_000;

        @Override
        public void append(DecisionLog entry) {
            synchronized (entries) {
                entries.addLast(entry);
                while (entries.size() > MAX_ENTRIES) {
                    entries.removeFirst();
                }
            }
        }

        @Override
        public List<DecisionLog> query(String decision, Long fromMs, Long toMs, int offset, int limit) {
            List<DecisionLog> snapshot;
            synchronized (entries) {
                snapshot = new ArrayList<>(entries);
            }
            int cappedLimit = Math.min(Math.max(limit, 1), 200);
            List<DecisionLog> out = snapshot.stream()
                    .filter(e -> decision == null || decision.isBlank() || e.decision().equals(decision))
                    .filter(e -> fromMs == null || e.atMs() >= fromMs)
                    .filter(e -> toMs == null || e.atMs() <= toMs)
                    .toList()
                    .reversed()
                    .stream()
                    .skip(Math.max(0, offset))
                    .limit(cappedLimit)
                    .toList();
            return out;
        }
    }

    private final DecisionLogStore store;
    private long sequence = 0;

    public DecisionLogRecorder(DecisionLogStore store) {
        this.store = store;
    }

    /** 记录一次评估（输入脱敏后落档） */
    public void record(long atMs, String subject, String object, String action,
            PolicyEngine.Decision decision, long costMs) {
        DecisionLog entry = new DecisionLog(nextId(), atMs,
                truncate(subject), truncate(object), truncate(action),
                decision.decision(), decision.hitStatementNames(), decision.fromCache(), costMs);
        try {
            store.append(entry);
        } catch (Exception e) {
            log.warn("决策日志落档失败（尽力而为）: {}", e.getMessage());
        }
    }

    public List<DecisionLog> query(String decision, Long fromMs, Long toMs, int offset, int limit) {
        return store.query(decision, fromMs, toMs, offset, limit);
    }

    /** 脱敏截断：null→"-"，超长截断加省略号 */
    static String truncate(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= MAX_SUMMARY_LEN ? value
                : value.substring(0, MAX_SUMMARY_LEN) + "…";
    }

    private synchronized long nextId() {
        return ++sequence;
    }
}
