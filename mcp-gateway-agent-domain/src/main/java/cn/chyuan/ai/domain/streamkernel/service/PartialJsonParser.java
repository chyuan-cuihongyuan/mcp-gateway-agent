package cn.chyuan.ai.domain.streamkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 部分JSON增量解析器（工单 0371 AT1，vercel/ai 流式 JSON 思想）。
 * 流式追加文本 → 根对象已完成字段即出（原始 JSON 值文本），悬挂键追踪（未闭合字段）。
 * 同流重放产出一致；增量产出与整段一次性解析等价。零依赖，纯状态机。
 */
public class PartialJsonParser {

    /** 已完成字段（根对象顶层）：键 + 原始 JSON 值文本 */
    public record FieldUpdate(String key, String jsonValue) {
    }

    /** 增量结果：本次新完成字段 + 当前悬挂键 + 根是否闭合 */
    public static class Delta {
        private final List<FieldUpdate> completed = new ArrayList<>();
        private final Set<String> pendingKeys;

        Delta(Set<String> pendingKeys) {
            this.pendingKeys = pendingKeys;
        }

        public List<FieldUpdate> getCompleted() {
            return completed;
        }

        public Set<String> getPendingKeys() {
            return pendingKeys;
        }

        public boolean isRootClosed() {
            return rootClosed;
        }

        private boolean rootClosed;
    }

    private final StringBuilder whole = new StringBuilder();
    private boolean inString;
    private boolean escaped;
    private int depth;
    private String currentKey;
    private final StringBuilder keyBuffer = new StringBuilder();
    private int valueStart = -1;
    private boolean rootClosed;

    /**
     * 喂入增量片段，返回本次新完成的字段与当前悬挂键。
     */
    public Delta feed(String chunk) {
        String text = chunk == null ? "" : chunk;
        int scanFrom = whole.length();
        whole.append(text);
        Delta delta = new Delta(currentPending());
        for (int i = scanFrom; i < whole.length() && !rootClosed; i++) {
            char ch = whole.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                    if (depth == 1) {
                        if (currentKey == null && valueStart < 0) {
                            currentKey = keyBuffer.toString();
                            keyBuffer.setLength(0);
                        } else if (valueStart >= 0) {
                            emit(delta, i);
                        }
                    }
                } else if (depth == 1 && currentKey == null && valueStart < 0) {
                    keyBuffer.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    inString = true;
                    if (depth == 1 && currentKey != null && valueStart < 0) {
                        valueStart = i;
                    }
                }
                case '{', '[' -> {
                    if (depth == 1 && currentKey != null && valueStart < 0) {
                        valueStart = i;
                    }
                    depth++;
                }
                case '}', ']' -> {
                    if (depth == 2 && currentKey != null && valueStart >= 0) {
                        emit(delta, i);
                    } else if (depth == 1 && currentKey != null && valueStart >= 0) {
                        emit(delta, i);
                    }
                    depth--;
                    if (depth == 0) {
                        rootClosed = true;
                        delta.rootClosed = true;
                    }
                }
                case ',' -> {
                    if (depth == 1 && currentKey != null && valueStart >= 0) {
                        emit(delta, i - 1);
                    }
                }
                case ':' -> {
                    // 键值分隔：不参与标量起点判定
                }
                default -> {
                    if (depth == 1 && currentKey != null && valueStart < 0
                            && !Character.isWhitespace(ch)) {
                        valueStart = i;
                    }
                }
            }
        }
        delta.pendingKeys.clear();
        delta.pendingKeys.addAll(currentPending());
        return delta;
    }

    /** 流是否整体闭合 */
    public boolean isComplete() {
        return rootClosed;
    }

    /** 当前悬挂（未完成）的键：键已闭合待取值，或键名本身仍在流式中 */
    public Set<String> currentPending() {
        Set<String> pending = new LinkedHashSet<>();
        if (rootClosed) {
            return pending;
        }
        if (currentKey != null) {
            pending.add(currentKey);
        } else if (keyBuffer.length() > 0) {
            pending.add(keyBuffer.toString());
        }
        return pending;
    }

    /** 已累计的整段文本（重放等价断言用） */
    public String consumed() {
        return whole.toString();
    }

    /** 产出字段并在状态上收尾该键值 */
    private void emit(Delta delta, int endAbs) {
        String value = whole.substring(valueStart, endAbs + 1).trim();
        if (!value.isEmpty()) {
            delta.completed.add(new FieldUpdate(currentKey, value));
        }
        currentKey = null;
        valueStart = -1;
    }
}
