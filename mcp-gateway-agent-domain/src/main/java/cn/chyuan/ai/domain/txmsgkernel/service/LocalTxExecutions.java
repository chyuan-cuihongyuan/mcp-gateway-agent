package cn.chyuan.ai.domain.txmsgkernel.service;

/**
 * 本地事务判定（工单 1136 EX2，rocketmq 思想）。
 * 执行 OK 提交/FAIL 回滚/UNKNOWN 待回查/执行异常按回滚（failNext 注入）。
 */
public final class LocalTxExecutions {

    /** 执行结论 */
    public enum Outcome { OK, FAIL, UNKNOWN }

    private String failNextBizKey;

    /** 注入下一次执行异常（测试口径）；未知业务键拒绝由调用方保障 */
    public void failNext(String bizKey) {
        failNextBizKey = bizKey;
    }

    /** 执行本地事务：异常时按 FAIL（回滚）；返回结论 */
    public Outcome execute(String bizKey, Outcome declared) {
        if (bizKey != null && bizKey.equals(failNextBizKey)) {
            failNextBizKey = null;
            throw new IllegalStateException("本地事务执行异常: " + bizKey);
        }
        if (declared == null) {
            throw new IllegalArgumentException("执行结论不能为空");
        }
        return declared;
    }

    /** 结论映射终局动作：OK→commit/FAIL→rollback/UNKNOWN→待回查 */
    public static String actionOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> "commit";
            case FAIL -> "rollback";
            case UNKNOWN -> "checkback";
        };
    }
}
