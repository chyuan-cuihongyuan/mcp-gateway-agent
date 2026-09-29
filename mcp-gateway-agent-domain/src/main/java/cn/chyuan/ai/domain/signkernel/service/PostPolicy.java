package cn.chyuan.ai.domain.signkernel.service;

import java.util.List;
import java.util.Map;

/**
 * POST policy（工单 1017 EJ5，minio POST policy 思想）。
 * eq 精确/startswith 前缀/条件不满足拒绝。
 */
public final class PostPolicy {

    /** 策略条件：eq 精确匹配 / starts-with 前缀匹配 */
    public record Condition(String op, String field, String value) {

        public Condition {
            if (!"eq".equals(op) && !"starts-with".equals(op)) {
                throw new IllegalArgumentException("未知条件算子: " + op);
            }
            if (field == null || field.isEmpty()) {
                throw new IllegalArgumentException("条件字段为空");
            }
            if (value == null) {
                throw new IllegalArgumentException("条件值为空: " + field);
            }
        }

        boolean matches(Map<String, String> form) {
            String actual = form.get(field);
            return switch (op) {
                case "eq" -> value.equals(actual);
                default -> actual != null && actual.startsWith(value);
            };
        }
    }

    /** 全条件核验：任一不满足拒绝 */
    public static void validate(List<Condition> conditions, Map<String, String> form) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("策略条件为空");
        }
        for (Condition condition : conditions) {
            if (!condition.matches(form)) {
                throw new IllegalStateException(
                        "策略条件不满足: " + condition.op() + " " + condition.field()
                                + " " + condition.value() + " (实际 " + form.get(condition.field()) + ")");
            }
        }
    }
}
