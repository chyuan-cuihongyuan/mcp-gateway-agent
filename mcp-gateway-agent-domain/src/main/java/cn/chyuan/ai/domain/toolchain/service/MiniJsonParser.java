package cn.chyuan.ai.domain.toolchain.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（AP1 内部：对象/数组/字符串/数字/布尔/null，无依赖）。
 */
final class MiniJsonParser {

    private final String text;
    private int pos;

    MiniJsonParser(String text) {
        this.text = text == null ? "" : text;
    }

    Object parse() {
        skip();
        Object value = parseValue();
        skip();
        if (pos < text.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容，位置 " + pos);
        }
        return value;
    }

    private Object parseValue() {
        skip();
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> expect("true", Boolean.TRUE);
            case 'f' -> expect("false", Boolean.FALSE);
            case 'n' -> expect("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skip();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skip();
            String key = parseString();
            skip();
            if (peek() != ':') {
                throw new IllegalArgumentException("JSON 缺冒号，位置 " + pos);
            }
            pos++;
            out.put(key, parseValue());
            skip();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return out;
            } else {
                throw new IllegalArgumentException("JSON 对象结构非法，位置 " + pos);
            }
        }
    }

    private List<Object> parseArray() {
        List<Object> out = new ArrayList<>();
        pos++;
        skip();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(parseValue());
            skip();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return out;
            } else {
                throw new IllegalArgumentException("JSON 数组结构非法，位置 " + pos);
            }
        }
    }

    private String parseString() {
        if (peek() != '"') {
            throw new IllegalArgumentException("JSON 缺引号，位置 " + pos);
        }
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c == '\\') {
                pos++;
                char esc = text.charAt(pos);
                out.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> esc;
                });
            } else {
                out.append(c);
            }
            pos++;
        }
    }

    private Number parseNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        try {
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数字非法: " + number);
        }
    }

    private Object expect(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw new IllegalArgumentException("JSON 字面量非法，位置 " + pos);
        }
        pos += literal.length();
        return value;
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        return text.charAt(pos);
    }

    private void skip() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
