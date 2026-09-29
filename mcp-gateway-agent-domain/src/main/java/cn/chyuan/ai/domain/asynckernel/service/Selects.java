package cn.chyuan.ai.domain.asynckernel.service;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * select 公平轮转（工单 0999 EH3，tokio select! 思想）。
 * 多路首就绪胜出/全未就绪等待/同 tick 就绪轮转起点递进。
 */
public final class Selects {

    /** select 分支：名字 + 就绪探针 */
    public record Branch(String name, BooleanSupplier ready) {

        public Branch {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("分支名为空");
            }
            if (ready == null) {
                throw new IllegalArgumentException("分支就绪探针为空: " + name);
            }
        }
    }

    /** 轮转起点：同 tick 多路就绪时递进保证公平 */
    private int start;

    /**
     * 多路等待：从轮转起点扫描，首就绪胜出并推进起点；
     * 全未就绪返回 null（等待，不空转）。
     */
    public synchronized String select(List<Branch> branches) {
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("select 分支为空");
        }
        int n = branches.size();
        for (int i = 0; i < n; i++) {
            int idx = Math.floorMod(start + i, n);
            Branch branch = branches.get(idx);
            if (branch.ready().getAsBoolean()) {
                start = Math.floorMod(idx + 1, n);
                return branch.name();
            }
        }
        return null;
    }

    public synchronized int start() {
        return start;
    }
}
