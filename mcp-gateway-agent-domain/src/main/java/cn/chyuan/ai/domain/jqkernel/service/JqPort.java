package cn.chyuan.ai.domain.jqkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 过滤端口（工单 0763 CL8，jq 思想）。
 * compile/run 入口统一编排/与 msgkernel 只读联动（JSON 载荷文本作过滤输入形态）/
 * jq-kernel.enabled 默认关（开启才改变行为）。
 */
public interface JqPort {

    /** 编译并执行：输入 JSON 值 → 输出流渲染 */
    List<String> run(String program, Object input);

    /** msgkernel 只读联动形态：JSON 载荷文本解析后作过滤输入（形状数据不 import msgkernel） */
    List<String> runOnJson(String program, String jsonText);

    /** 单值渲染 */
    static String render(Object value) {
        return JqEvaluator.render(value);
    }

    /** 内存实现 */
    static JqPort inMemory() {
        return new InMemoryJq();
    }
}

final class InMemoryJq implements JqPort {

    @Override
    public List<String> run(String program, Object input) {
        JqParser.Node ast = JqParser.of(program).parse();
        JqEvaluator evaluator = new JqEvaluator(ast);
        List<String> out = new ArrayList<>();
        for (Object value : evaluator.run(input)) {
            out.add(JqEvaluator.render(value));
        }
        return out;
    }

    @Override
    public List<String> runOnJson(String program, String jsonText) {
        return run(program, JsonLite.parse(jsonText));
    }
}

/** 极简 JSON 解析（载荷形状互操作用，零依赖） */
final class JsonLite {

    private JsonLite() {
    }

    static Object parse(String json) {
        return new Reader(json).parseValue();
    }

    private static final class Reader {
        private final String s;
        private int at;

        Reader(String s) {
            this.s = s;
        }

        Object parseValue() {
            skip();
            char c = s.charAt(at);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> word("true", true);
                case 'f' -> word("false", false);
                case 'n' -> word("null", null);
                default -> parseNumber();
            };
        }

        Object word(String w, Object v) {
            at += w.length();
            return v;
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skip();
            if (s.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                skip();
                String key = parseString();
                skip();
                at++;
                map.put(key, parseValue());
                skip();
                if (s.charAt(at) == ',') {
                    at++;
                    continue;
                }
                at++;
                return map;
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            at++;
            skip();
            if (s.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skip();
                if (s.charAt(at) == ',') {
                    at++;
                    continue;
                }
                at++;
                return list;
            }
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            at++;
            while (s.charAt(at) != '"') {
                char c = s.charAt(at);
                if (c == '\\') {
                    at++;
                    sb.append(switch (s.charAt(at)) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> s.charAt(at);
                    });
                } else {
                    sb.append(c);
                }
                at++;
            }
            at++;
            return sb.toString();
        }

        Double parseNumber() {
            skip();
            int start = at;
            while (at < s.length() && (Character.isDigit(s.charAt(at)) || s.charAt(at) == '-' || s.charAt(at) == '.')) {
                at++;
            }
            return Double.parseDouble(s.substring(start, at));
        }

        void skip() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }
    }
}
