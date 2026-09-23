package cn.chyuan.ai.domain.machinekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 状态图解释器（工单 0618 BV1 + 0619 BV2 + 0620 BV3 + 0621 BV4，XState statechart 思想）。
 * 状态节点与事件迁移表/初始态/未定义事件策略（拒绝或自环忽略，可配置）；
 * 守卫谓词（false 依序尝试下一候选）/进入·退出·迁移动作副作用（exit→transition→entry 确定性顺序）；
 * 复合态初始子态/事件由活跃子态向祖先冒泡/退出先深后浅、进入先浅后深；
 * 并行态多区域同时活跃/区域全终则并行态完成触发 done 事件到父/最终态后事件拒绝。
 */
public final class Statechart {

    /** 迁移规则：源状态+事件 → 目标 + 守卫 + 动作 */
    public static final class Rule {
        final String from;
        final String event;
        final String to;
        final Predicate<Map<String, Object>> guard;
        final String action;

        Rule(String from, String event, String to, Predicate<Map<String, Object>> guard, String action) {
            this.from = from;
            this.event = event;
            this.to = to;
            this.guard = guard;
            this.action = action;
        }
    }

    /** 未定义事件策略 */
    public enum UndefinedPolicy {
        REJECT, IGNORE
    }

    /** 状态节点定义 */
    private static final class Node {
        String id;
        String parent;
        List<String> children = new ArrayList<>();
        List<String> regionNames = new ArrayList<>();
        String initialChild;
        boolean parallel;
        boolean finalState;
        List<String> entryActions = new ArrayList<>();
        List<String> exitActions = new ArrayList<>();
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final String rootInitial;
    private final UndefinedPolicy policy;

    private Statechart(Builder builder) {
        this.nodes.putAll(builder.nodes);
        this.rules.addAll(builder.rules);
        this.rootInitial = builder.rootInitial;
        this.policy = builder.policy;
    }

    public static Builder builder(String initialState) {
        return new Builder(initialState);
    }

    /** 建造器 */
    public static final class Builder {

        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Rule> rules = new ArrayList<>();
        private final String rootInitial;
        private UndefinedPolicy policy = UndefinedPolicy.REJECT;

        private Builder(String rootInitial) {
            this.rootInitial = rootInitial;
        }

        /** 未定义事件策略（默认 REJECT） */
        public Builder undefinedPolicy(UndefinedPolicy p) {
            this.policy = p;
            return this;
        }

        /** 原子态（父为 null=根层） */
        public Builder state(String id) {
            return state(id, null);
        }

        public Builder state(String id, String parent) {
            Node node = new Node();
            node.id = id;
            node.parent = parent;
            nodes.put(id, node);
            if (parent != null) {
                Node parentNode = require(parent);
                if (!parentNode.children.contains(id)) {
                    parentNode.children.add(id);
                }
            }
            return this;
        }

        /** 复合态（进入时落入 initial 指定的子态） */
        public Builder composite(String id, String initialChild) {
            state(id);
            require(id).initialChild = initialChild;
            return this;
        }

        /** 并行态（regions 为区域复合态 id 列表，build 时接线） */
        public Builder parallel(String id, List<String> regions) {
            state(id);
            Node node = require(id);
            node.parallel = true;
            node.regionNames.addAll(regions == null ? List.of() : regions);
            return this;
        }

        /** 最终态（所属区域全终触发 done） */
        public Builder finalState(String id) {
            state(id);
            require(id).finalState = true;
            return this;
        }

        /** 最终态（挂指定父域） */
        public Builder finalState(String id, String parent) {
            state(id, parent);
            require(id).finalState = true;
            return this;
        }

        /** 基本迁移 */
        public Builder transition(String from, String event, String to) {
            rules.add(new Rule(from, event, to, null, null));
            return this;
        }

        /** 守卫+动作迁移（false 守卫依序尝试下一候选） */
        public Builder transition(String from, String event, String to,
                                  Predicate<Map<String, Object>> guard, String action) {
            rules.add(new Rule(from, event, to, guard, action));
            return this;
        }

        /** 进入动作 */
        public Builder onEntry(String state, String action) {
            require(state).entryActions.add(action);
            return this;
        }

        /** 退出动作 */
        public Builder onExit(String state, String action) {
            require(state).exitActions.add(action);
            return this;
        }

        private Node require(String id) {
            Node node = nodes.get(id);
            if (node == null) {
                throw new IllegalArgumentException("未定义状态：" + id);
            }
            return node;
        }

        public Statechart build() {
            if (!nodes.containsKey(rootInitial)) {
                throw new IllegalArgumentException("初始态未定义：" + rootInitial);
            }
            for (Node node : nodes.values()) {
                if (node.initialChild != null) {
                    link(node, node.initialChild);
                }
                for (String region : node.regionNames) {
                    link(node, region);
                }
            }
            for (Rule rule : rules) {
                if (!nodes.containsKey(rule.from) || !nodes.containsKey(rule.to)) {
                    throw new IllegalArgumentException("迁移端点未定义：" + rule.from + "->" + rule.to);
                }
            }
            return new Statechart(this);
        }

        private void link(Node parent, String childId) {
            Node child = require(childId);
            if (child.parent != null && !child.parent.equals(parent.id)) {
                throw new IllegalArgumentException("状态 " + childId + " 已挂 " + child.parent + "，不能再挂 " + parent.id);
            }
            child.parent = parent.id;
            if (!parent.children.contains(childId)) {
                parent.children.add(childId);
            }
        }
    }

    /** 解释器实例 */
    public final class Interpreter {

        private final Set<String> active = new LinkedHashSet<>();
        private final Map<String, Long> entryCounts = new LinkedHashMap<>();
        private final Map<String, Object> context = new LinkedHashMap<>();
        private final List<String> actionLog = new ArrayList<>();

        Interpreter() {
            enter(rootInitial);
        }

        /** 派发事件：返回执行的动作序列（exit→transition→entry） */
        public synchronized List<String> send(String event) {
            return send(event, context);
        }

        public synchronized List<String> send(String event, Map<String, Object> ctx) {
            for (String leaf : new ArrayList<>(activeLeaves())) {
                String current = leaf;
                while (current != null) {
                    Rule hit = selectRule(current, event, ctx);
                    if (hit != null) {
                        int from = actionLog.size();
                        fire(hit);
                        return List.copyOf(actionLog.subList(from, actionLog.size()));
                    }
                    current = nodes.get(current).parent;
                }
            }
            if (policy == UndefinedPolicy.REJECT) {
                throw new IllegalArgumentException("未定义事件：" + event + " @ " + activeLeaves());
            }
            return List.of();
        }

        private Rule selectRule(String state, String event, Map<String, Object> ctx) {
            for (Rule rule : rules) {
                if (rule.from.equals(state) && rule.event.equals(event)
                        && (rule.guard == null || rule.guard.test(ctx))) {
                    return rule;
                }
            }
            return null;
        }

        private void fire(Rule rule) {
            exitSubtree(exitScope(rule));
            if (rule.action != null) {
                actionLog.add(rule.action);
            }
            enter(rule.to);
            afterSettle();
        }

        /** 退出范围：并行区域内的迁移目标在并行外 → 退出整个并行（XState 语义） */
        private String exitScope(Rule rule) {
            String scope = rule.from;
            String cur = nodes.get(rule.from).parent;
            while (cur != null) {
                if (nodes.get(cur).parallel && !inSubtree(rule.to, cur)) {
                    scope = cur;
                }
                cur = nodes.get(cur).parent;
            }
            return scope;
        }

        /** 进入态（先浅后深：复合态落 initial，并行态激活全部区域叶） */
        private void enter(String id) {
            Node node = nodes.get(id);
            for (String action : node.entryActions) {
                actionLog.add(action);
            }
            active.add(id);
            entryCounts.merge(id, 1L, Long::sum);
            if (node.parallel) {
                for (String region : node.children) {
                    enter(region);
                }
            } else if (node.initialChild != null) {
                enter(node.initialChild);
            } else if (!node.children.isEmpty()) {
                enter(node.children.get(0));
            }
        }

        /** 终态收敛：区域内活跃叶全终 → 并行 done 事件到父链 */
        private void afterSettle() {
            boolean progressed = true;
            int guard = 0;
            while (progressed && guard++ < 32) {
                progressed = false;
                for (String id : new ArrayList<>(active)) {
                    Node node = nodes.get(id);
                    if (!node.parallel || node.children.isEmpty()) {
                        continue;
                    }
                    if (regionAllFinal(id)) {
                        Rule done = selectRule(id, "done." + id, context);
                        if (done != null) {
                            fireQuiet(done, context);
                            progressed = true;
                        }
                    }
                }
            }
        }

        private void fireQuiet(Rule rule, Map<String, Object> ctx) {
            exitSubtree(exitScope(rule));
            if (rule.action != null) {
                actionLog.add(rule.action);
            }
            enter(rule.to);
        }

        /** 区域完成口径：该子树内每个活跃叶都是终态（至少一个活跃叶） */
        private boolean regionAllFinal(String region) {
            boolean anyActive = false;
            for (String leaf : activeLeaves()) {
                if (inSubtree(leaf, region)) {
                    anyActive = true;
                    if (!nodes.get(leaf).finalState) {
                        return false;
                    }
                }
            }
            return anyActive;
        }

        private void exitSubtree(String from) {
            List<String> exited = new ArrayList<>();
            for (String leaf : new ArrayList<>(active)) {
                String cur = leaf;
                while (cur != null && !cur.equals(from)) {
                    cur = nodes.get(cur).parent;
                }
                if (from.equals(cur)) {
                    cur = leaf;
                    while (cur != null && !cur.equals(from)) {
                        exited.add(cur);
                        cur = nodes.get(cur).parent;
                    }
                }
            }
            exited.add(from);
            for (String state : exited) {
                for (String action : nodes.get(state).exitActions) {
                    actionLog.add(action);
                }
                active.remove(state);
            }
        }


        private boolean isLeaf(String id) {
            return nodes.get(id).children.isEmpty();
        }

        private boolean inSubtree(String candidate, String ancestor) {
            String cur = candidate;
            while (cur != null) {
                if (cur.equals(ancestor)) {
                    return true;
                }
                cur = nodes.get(cur).parent;
            }
            return false;
        }

        /** 活跃叶集合（每区域一叶） */
        public synchronized Set<String> activeLeaves() {
            Set<String> leaves = new LinkedHashSet<>();
            for (String id : active) {
                if (isLeaf(id)) {
                    leaves.add(id);
                }
            }
            return leaves;
        }

        /** 根到叶路径 */
        public synchronized List<String> pathTo(String leaf) {
            List<String> path = new ArrayList<>();
            String cur = leaf;
            while (cur != null) {
                path.add(0, cur);
                cur = nodes.get(cur).parent;
            }
            return path;
        }

        /** 是否在终态 */
        public synchronized boolean isFinal(String id) {
            return nodes.get(id).finalState && active.contains(id);
        }

        /** 状态进入次数（审计） */
        public synchronized long entryCount(String id) {
            return entryCounts.getOrDefault(id, 0L);
        }

        /** 上下文 */
        public synchronized Map<String, Object> context() {
            return context;
        }

        /** 动作日志（累计） */
        public synchronized List<String> actionLog() {
            return List.copyOf(actionLog);
        }

        public synchronized void clearActionLog() {
            actionLog.clear();
        }

        /** 可序列化快照：活跃态+上下文+进入次数 */
        public synchronized MachineSnapshots.Snapshot snapshot() {
            return new MachineSnapshots.Snapshot(new LinkedHashSet<>(active),
                    new LinkedHashMap<>(context), new LinkedHashMap<>(entryCounts));
        }

        /** 恢复快照：继续接受事件，迁移语义不变 */
        public synchronized void restore(MachineSnapshots.Snapshot snapshot) {
            if (snapshot == null) {
                throw new IllegalArgumentException("快照不得为 null");
            }
            active.clear();
            active.addAll(snapshot.activeStates());
            context.clear();
            context.putAll(snapshot.context());
            entryCounts.clear();
            entryCounts.putAll(snapshot.entryCounts());
        }
    }

    /** 启动解释器 */
    public synchronized Interpreter start() {
        return new Interpreter();
    }

    /** 状态存在性 */
    public synchronized boolean hasState(String id) {
        return nodes.containsKey(id);
    }
}
