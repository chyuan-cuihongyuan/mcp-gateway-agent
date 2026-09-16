package cn.chyuan.ai.domain.llmcache.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 前缀基数树（工单 0278 AJ2，借鉴 SGLang RadixAttention）—
 * 键序列按链插入（路径即前缀链）；最长前缀匹配返回匹配深度；
 * 深度与节点数上限防护 + 过期剪枝接口 + 复用收益统计（匹配深度 × 块均 token 估算）。
 *
 * @author chyuan
 */
public class PrefixRadixTree {

    /** 默认块均 token 估算（收益统计口径） */
    public static final int DEFAULT_TOKENS_PER_BLOCK = 256;

    /** 树节点（键值即链哈希，天然无分叉合并需求——链式哈希后同前缀同键） */
    static class Node {
        final Map<String, Node> children = new HashMap<>();
        boolean terminal;
    }

    private final Node root = new Node();
    private final int maxDepth;
    private final int maxNodes;
    private int nodeCount;
    private long savedTokens;
    private final int tokensPerBlock;

    public PrefixRadixTree(int maxDepth, int maxNodes, int tokensPerBlock) {
        this.maxDepth = Math.max(1, maxDepth);
        this.maxNodes = Math.max(1, maxNodes);
        this.tokensPerBlock = Math.max(1, tokensPerBlock);
        this.nodeCount = 1;
    }

    public PrefixRadixTree() {
        this(64, 65_536, DEFAULT_TOKENS_PER_BLOCK);
    }

    /** 插入键序列（超深度/超节点拒绝返回 false） */
    public synchronized boolean insert(List<String> keys) {
        if (keys.isEmpty() || keys.size() > maxDepth) {
            return false;
        }
        if (nodeCount + (keys.size() - countExistingPrefix(keys)) > maxNodes) {
            return false;
        }
        Node current = root;
        for (String key : keys) {
            Node next = current.children.get(key);
            if (next == null) {
                next = new Node();
                current.children.put(key, next);
                nodeCount++;
            }
            current = next;
        }
        current.terminal = true;
        return true;
    }

    /** 最长前缀匹配：返回匹配块数（0=无匹配） */
    public synchronized int longestMatch(List<String> keys) {
        Node current = root;
        int matched = 0;
        for (String key : keys) {
            Node next = current.children.get(key);
            if (next == null) {
                break;
            }
            matched++;
            current = next;
        }
        if (matched > 0) {
            savedTokens += (long) matched * tokensPerBlock;
        }
        return matched;
    }

    /** 剪枝：访问时间早于 given 毫秒时间戳的叶子由调用方维护——此处按过期队列深度清理陈旧分支接口 */
    public interface ExpiryIndex {

        /** 返回疑似过期的末端键（供树上按键路径剪除） */
        List<String> expiredTailKeys();
    }

    /** 按末端键剪除分支（返回直接移除的末端节点数；断链的孤儿中间节点一并回收） */
    public synchronized int pruneTail(String tailKey) {
        int removed = 0;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            Node child = node.children.get(tailKey);
            if (child != null) {
                node.children.remove(tailKey);
                nodeCount--;
                removed++;
            }
            stack.addAll(node.children.values());
        }
        // 断链回收：孩子清空且非末端的中间节点随分支一并剪除（root 除外，不计入返回数）
        boolean changed = true;
        while (changed) {
            changed = false;
            Deque<Node> sweep = new ArrayDeque<>();
            sweep.push(root);
            while (!sweep.isEmpty()) {
                Node node = sweep.pop();
                for (java.util.Iterator<Node> it = node.children.values().iterator(); it.hasNext(); ) {
                    Node candidate = it.next();
                    if (candidate.children.isEmpty() && !candidate.terminal) {
                        it.remove();
                        nodeCount--;
                        changed = true;
                    } else {
                        sweep.push(candidate);
                    }
                }
            }
        }
        return removed;
    }

    /** 树快照统计 */
    public synchronized Map<String, Object> snapshot() {
        return Map.of(
                "nodes", nodeCount,
                "maxDepth", maxDepth,
                "maxNodes", maxNodes,
                "savedTokens", savedTokens,
                "tokensPerBlock", tokensPerBlock);
    }

    public synchronized long savedTokens() {
        return savedTokens;
    }

    public synchronized int nodeCount() {
        return nodeCount;
    }

    /** 已存在前缀长度（容量估算用） */
    private int countExistingPrefix(List<String> keys) {
        Node current = root;
        int existing = 0;
        for (String key : keys) {
            Node next = current.children.get(key);
            if (next == null) {
                break;
            }
            existing++;
            current = next;
        }
        return existing;
    }
}
