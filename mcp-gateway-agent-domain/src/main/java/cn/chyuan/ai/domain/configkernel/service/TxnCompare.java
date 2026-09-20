package cn.chyuan.ai.domain.configkernel.service;

import java.util.List;

/**
 * 事务比较（工单 0468 BD5，etcd txn 思想）。
 * compare 版本/值/创建版本三类比较 + 成功失败分支动作（put/delete）+ 原子应用
 * （分支选取整体一致，动作在 MVCC 存储上顺序应用）。
 */
public class TxnCompare {

    /** 比较字段 */
    public enum Field {
        VERSION, VALUE, CREATE_REVISION
    }

    /** 比较算子 */
    public enum Op {
        EQ, NEQ, GT, LT
    }

    /** 一条比较：字段 + 算子 + 键 + 期望值（value 字段用 expectedValue，数值字段用 expectedNumber） */
    public record Compare(Field field, Op op, String key, long expectedNumber, String expectedValue) {
        public static Compare version(Op op, String key, long expected) {
            return new Compare(Field.VERSION, op, key, expected, null);
        }

        public static Compare value(Op op, String key, String expected) {
            return new Compare(Field.VALUE, op, key, 0L, expected);
        }

        public static Compare createRevision(Op op, String key, long expected) {
            return new Compare(Field.CREATE_REVISION, op, key, expected, null);
        }
    }

    /** 分支动作 */
    public record Action(Type type, String key, String value) {
        public enum Type {
            PUT, DELETE
        }

        public static Action put(String key, String value) {
            return new Action(Type.PUT, key, value);
        }

        public static Action delete(String key) {
            return new Action(Type.DELETE, key, null);
        }
    }

    /** 应用结果：分支判定 + 各动作分配的 revision（delete no-op 为 0） */
    public record TxnResult(boolean branchTaken, List<Long> actionRevisions) {
    }

    /** 原子应用：全部比较为真走 success 分支，否则 failure 分支 */
    public TxnResult apply(MvccStore store, List<Compare> compares,
            List<Action> successActions, List<Action> failureActions) {
        boolean allMatch = compares.stream().allMatch(compare -> evaluate(store, compare));
        List<Action> actions = allMatch ? successActions : failureActions;
        List<Long> revisions = actions.stream()
                .map(action -> apply(store, action))
                .toList();
        return new TxnResult(allMatch, revisions);
    }

    private boolean evaluate(MvccStore store, Compare compare) {
        MvccStore.HistoryResult current = store.get(compare.key());
        return switch (compare.field()) {
            case VERSION -> compareNumeric(store.versionChain(compare.key()).size(), compare);
            case CREATE_REVISION -> compareNumeric(current.present() ? current.revision() : 0L, compare);
            case VALUE -> compareValue(current.present() ? current.value() : null, compare);
        };
    }

    private boolean compareNumeric(long actual, Compare compare) {
        return switch (compare.op()) {
            case EQ -> actual == compare.expectedNumber();
            case NEQ -> actual != compare.expectedNumber();
            case GT -> actual > compare.expectedNumber();
            case LT -> actual < compare.expectedNumber();
        };
    }

    private boolean compareValue(String actual, Compare compare) {
        if (actual == null && compare.expectedValue() == null) {
            return compare.op() == Op.EQ;
        }
        if (actual == null || compare.expectedValue() == null) {
            return compare.op() == Op.NEQ;
        }
        int order = actual.compareTo(compare.expectedValue());
        return switch (compare.op()) {
            case EQ -> order == 0;
            case NEQ -> order != 0;
            case GT -> order > 0;
            case LT -> order < 0;
        };
    }

    private long apply(MvccStore store, Action action) {
        return switch (action.type()) {
            case PUT -> store.put(action.key(), action.value());
            case DELETE -> store.delete(action.key());
        };
    }
}
