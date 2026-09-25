package cn.chyuan.ai.domain.muxkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前缀键绑定与粘贴缓冲（工单 0850-0851 CV5·CV6，tmux 思想）。
 * 前缀键 chord 绑定冲突拒绝/键表查询/粘贴缓冲栈 LIFO 与上限淘汰/具名缓冲。
 */
public final class KeyTable {

    private final Map<String, String> bindings = new LinkedHashMap<>();
    private String prefix = "C-b";

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /** 绑定 chord → 动作；冲突拒绝 */
    public void bind(String chord, String action) {
        if (bindings.containsKey(chord) && !bindings.get(chord).equals(action)) {
            throw new IllegalArgumentException("chord 冲突: " + chord);
        }
        bindings.put(chord, action);
    }

    public void unbind(String chord) {
        bindings.remove(chord);
    }

    /** 查询：未绑定返回 null */
    public String lookup(String chord) {
        return bindings.get(chord);
    }

    public int size() {
        return bindings.size();
    }

    /** 前缀+chord 组合按键解析：未按前缀返回 none */
    public String press(boolean prefixPressed, String chord) {
        if (!prefixPressed) {
            return "none";
        }
        String action = bindings.get(chord);
        return action == null ? "unknown" : action;
    }

    /** 粘贴缓冲：栈 + 具名 + 上限 */
    public static final class PasteBuffer {
        private final Deque<String> stack = new ArrayDeque<>();
        private final Map<String, String> named = new LinkedHashMap<>();
        private final int cap;

        public PasteBuffer(int cap) {
            if (cap <= 0) {
                throw new IllegalArgumentException("缓冲上限须为正");
            }
            this.cap = cap;
        }

        /** 压栈：超限淘汰最旧 */
        public String push(String content) {
            stack.addLast(content);
            while (stack.size() > cap) {
                stack.removeFirst();
            }
            return content;
        }

        /** 弹出（LIFO） */
        public String pop() {
            String v = stack.pollLast();
            if (v == null) {
                throw new IllegalStateException("缓冲为空");
            }
            return v;
        }

        public String put(String name, String content) {
            named.put(name, content);
            return content;
        }

        public String get(String name) {
            String v = named.get(name);
            if (v == null) {
                throw new IllegalArgumentException("未知具名缓冲: " + name);
            }
            return v;
        }

        public int size() {
            return stack.size();
        }
    }
}
