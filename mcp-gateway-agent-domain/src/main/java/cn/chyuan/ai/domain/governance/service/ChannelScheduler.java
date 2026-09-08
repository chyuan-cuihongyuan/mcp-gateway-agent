package cn.chyuan.ai.domain.governance.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 渠道调度器（工单 0060，one-api Priority+Weight 口径）
 *
 * <p>可用候选中取最高 priority 层，层内按 weight 加权随机；无可用候选返回 empty
 * （调用方决定回退语义：LLM 渠道故障转移 / MCP 全不可用报错）。
 * 可用性过滤（状态三态 + 冷却）由调用方完成——MCP 侧 enabledAttaches(status==1)
 * 已等价覆盖冷却（冷却只随 AUTO_DISABLED 置位）；LLM 渠道侧按同口径过滤后进入本调度器。
 *
 * @author chyuan
 */
@Service
public class ChannelScheduler {

    /** 候选渠道抽象（调度只关心三要素） */
    public record Candidate(String id, int priority, int weight) {
    }

    /**
     * 加权随机选一个渠道：最高 priority 层内按 weight 掷骰（weight<=0 按 1 计）。
     */
    public Optional<Candidate> pick(List<Candidate> candidates) {
        return pick(candidates, ThreadLocalRandom.current());
    }

    /** 可注入随机的重载（统计性测试用） */
    public Optional<Candidate> pick(List<Candidate> candidates, Random random) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        int maxPriority = candidates.stream().mapToInt(Candidate::priority).max().orElse(0);
        List<Candidate> tier = candidates.stream()
                .filter(c -> c.priority() == maxPriority)
                .toList();
        int totalWeight = tier.stream().mapToInt(c -> Math.max(1, c.weight())).sum();
        int dice = random.nextInt(totalWeight);
        int accumulated = 0;
        for (Candidate candidate : tier) {
            accumulated += Math.max(1, candidate.weight());
            if (dice < accumulated) {
                return Optional.of(candidate);
            }
        }
        return Optional.of(tier.get(tier.size() - 1));
    }
}
