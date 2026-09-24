package cn.chyuan.ai.domain.jqkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 值模型（工单 0762 CL7，jq 思想）。
 * null/布尔/数字/字符串/数组/对象六形态与跨类型比较定序
 * （null&lt;false&lt;true&lt;数字&lt;字符串&lt;数组&lt;对象）/truthy 规则/相等判定。
 */
public final class JqValue {

    public static final Object NULL = null;

    private JqValue() {
    }

    public static boolean isTruthy(Object v) {
        return !(v == null || Boolean.FALSE.equals(v));
    }

    public static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof List) {
            return "array";
        }
        return "object";
    }

    public static int typeRank(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean) {
            return Boolean.TRUE.equals(v) ? 2 : 1;
        }
        if (v instanceof Number) {
            return 3;
        }
        if (v instanceof String) {
            return 4;
        }
        if (v instanceof List) {
            return 5;
        }
        return 6;
    }

    /** 跨类型比较定序（同类型深比较） */
    public static int compare(Object a, Object b) {
        int ra = typeRank(a);
        int rb = typeRank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        switch (ra) {
            case 0, 1 -> {
                return 0;
            }
            case 2 -> {
                return 0;
            }
            case 3 -> {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            case 4 -> {
                return ((String) a).compareTo(b.toString());
            }
            case 5 -> {
                List<?> la = (List<?>) a;
                List<?> lb = (List<?>) b;
                int n = Math.min(la.size(), lb.size());
                for (int i = 0; i < n; i++) {
                    int c = compare(la.get(i), lb.get(i));
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(la.size(), lb.size());
            }
            default -> {
                Map<String, Object> ma = (Map<String, Object>) a;
                Map<String, Object> mb = (Map<String, Object>) b;
                List<String> ka = new ArrayList<>(ma.keySet());
                List<String> kb = new ArrayList<>(mb.keySet());
                ka.sort(Comparator.naturalOrder());
                kb.sort(Comparator.naturalOrder());
                int keyCompare = compare(ka, kb);
                if (keyCompare != 0) {
                    return keyCompare;
                }
                for (String key : ka) {
                    int c = compare(ma.get(key), mb.get(key));
                    if (c != 0) {
                        return c;
                    }
                }
                return 0;
            }
        }
    }

    public static boolean deepEquals(Object a, Object b) {
        return compare(a, b) == 0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    public static List<Object> array() {
        return new ArrayList<>();
    }

    /** 排序器（sort/unique/sort_by 用） */
    public static Comparator<Object> comparator() {
        return JqValue::compare;
    }

    public static String numberToString(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }
}
