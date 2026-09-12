package cn.chyuan.ai.domain.generation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标注回复服务（工单 0202 AA7，借鉴 Dify annotation reply）—
 * 高频问答对命中即直接回复，不再调用模型：归一化（去空白/小写）精确命中优先，
 * 可选编辑距离兜底（默认阈值 0.0=关闭，防止误答）。命中计数异步累加留痕。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class AnnotationReplyService {

    /** 标注持久化端口（infrastructure 经 MyBatis 落 mcp_annotation_qa 表） */
    public interface AnnotationStore {

        /** 启用态全部问答对 */
        List<AnnotationQa> listEnabled();

        void insert(AnnotationQa qa);

        void update(AnnotationQa qa);

        void delete(Long id);

        AnnotationQa findById(Long id);

        void incrementHit(Long id);

        /** 归一化键查重（编辑距离兜底用全量比对，精确命中走键直查） */
        AnnotationQa findByKey(String questionKey);
    }

    /** 问答对值对象 */
    public record AnnotationQa(Long id, String question, String answer, long hitCount,
            boolean enabled, String operator) {

        public AnnotationQa {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question 不能为空");
            }
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("answer 不能为空");
            }
        }
    }

    /** 命中结果 */
    public record AnnotationHit(Long qaId, String answer, String matchedQuestion, boolean fuzzy) {
    }

    private final AnnotationStore store;

    /** 编辑距离兜底阈值（归一化长度归一，0=关闭） */
    private final double fuzzyThreshold;

    public AnnotationReplyService(AnnotationStore store) {
        this(store, 0.0d);
    }

    /** 测试/配置化：显式兜底阈值 */
    public AnnotationReplyService(AnnotationStore store, double fuzzyThreshold) {
        this.store = store;
        this.fuzzyThreshold = fuzzyThreshold;
    }

    /** 问题归一化键：去全部空白 + 小写 */
    public static String normalizeKey(String question) {
        return question == null ? "" : question.replaceAll("\\s+", "").toLowerCase();
    }

    /** 查找命中：精确 → 编辑距离兜底（≤阈值）；未命中返回 null（调用方继续走模型） */
    public AnnotationHit find(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String key = normalizeKey(question);
        AnnotationQa exact = store.findByKey(key);
        if (exact != null && exact.enabled()) {
            return new AnnotationHit(exact.id(), exact.answer(), exact.question(), false);
        }
        if (fuzzyThreshold <= 0) {
            return null;
        }
        for (AnnotationQa qa : store.listEnabled()) {
            double similarity = similarity(key, normalizeKey(qa.question()));
            if (similarity >= fuzzyThreshold) {
                return new AnnotationHit(qa.id(), qa.answer(), qa.question(), true);
            }
        }
        return null;
    }

    /** 命中计数（尽力而为，失败不影响主链路） */
    public void recordHit(AnnotationHit hit) {
        if (hit == null || hit.qaId() == null) {
            return;
        }
        try {
            store.incrementHit(hit.qaId());
        } catch (Exception e) {
            log.warn("标注命中计数失败: id={}, err={}", hit.qaId(), e.getMessage());
        }
    }

    /** 编辑距离相似度（1-距离/最大长度，Levenshtein 双行滚动实现） */
    public static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        int distance = prev[b.length()];
        return 1.0d - (double) distance / Math.max(a.length(), b.length());
    }
}
