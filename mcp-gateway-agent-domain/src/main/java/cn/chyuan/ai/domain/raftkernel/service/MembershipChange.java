package cn.chyuan.ai.domain.raftkernel.service;

import java.util.Set;

/**
 * 单步成员变更（工单 0517 BJ6，raft single-server membership change 思想）。
 * 一次只增或删一节点（联合配置并发变更拒绝）/变更作为日志条目提交生效/
 * 新配置多数派按新成员集合计算。
 */
public class MembershipChange {

    /** 变更类型 */
    public enum Op {
        ADD, REMOVE
    }

    /** 变更结果：新配置 + 新多数派数 */
    public record Config(Set<String> members, int majority) {
        public Config {
            if (members.isEmpty()) {
                throw new IllegalArgumentException("配置不可为空");
            }
        }

        public boolean isMajority(int votes) {
            return votes >= majority;
        }
    }

    private Set<String> current = Set.of("n1", "n2", "n3");
    private boolean changeInFlight;

    /** 当前配置 */
    public synchronized Config config() {
        return new Config(current, current.size() / 2 + 1);
    }

    /** 提交单步变更：一次一节点，重复未决变更拒绝（变更日志条目提交前由调用方判定生效） */
    public synchronized Config propose(Op op, String node) {
        if (changeInFlight) {
            throw new IllegalStateException("已有未决成员变更（单步变更不允许并发）");
        }
        java.util.Set<String> next = new java.util.LinkedHashSet<>(current);
        boolean mutated = switch (op) {
            case ADD -> next.add(node);
            case REMOVE -> {
                if (current.size() == 1) {
                    throw new IllegalStateException("不可删除最后节点");
                }
                yield next.remove(node);
            }
        };
        if (!mutated) {
            return new Config(current, current.size() / 2 + 1);
        }
        changeInFlight = true;
        current = java.util.Collections.unmodifiableSet(next);
        return new Config(current, current.size() / 2 + 1);
    }

    /** 变更日志条目提交生效：解除未决标志 */
    public synchronized void committed() {
        changeInFlight = false;
    }

    public synchronized boolean hasPendingChange() {
        return changeInFlight;
    }
}
