package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YATA 文本序列（工单 0606 BT5，yjs YATA/RGA 思想）。
 * 双链插入（left/right origin 元数据随条目复制）/并发插入确定性整合
 * （lamport 时钟随条目复制：本地 1+max(双源, 本地钟)；落位=锚点后
 * 跳过排序键更低条目——低键恒在左、高键恒在右，后发并发连 run 靠右聚合
 * 防交错）/delete 墓碑标记/字符位置投影；整合按因果序（origin 先到），
 * 条目集相同则多副本收敛到同一线性序（键全内在，与到达序无关）。
 */
public final class YataText {

    /** 条目 id：副本+序号 */
    public record ItemId(String replica, long seq) {

        public ItemId {
            if (replica == null || replica.isBlank()) {
                throw new IllegalArgumentException("副本标识不得为空");
            }
            if (seq <= 0) {
                throw new IllegalArgumentException("序号须为正");
            }
        }
    }

    /** 导出形态（反熵联动面：lamport 随条目复制，不可重算） */
    public record ItemData(ItemId id, char value, ItemId originLeft, ItemId originRight,
                           boolean deleted, long lamport) {
    }

    /** 链表条目 */
    private static final class Item {
        final ItemId id;
        final char value;
        final ItemId originLeft;
        final ItemId originRight;
        boolean deleted;
        final long lamport;
        Item prev;
        Item next;

        Item(ItemId id, char value, ItemId originLeft, ItemId originRight, long lamport) {
            this.id = id;
            this.value = value;
            this.originLeft = originLeft;
            this.originRight = originRight;
            this.lamport = lamport;
        }
    }

    private final Map<ItemId, Item> items = new LinkedHashMap<>();
    private final Map<String, Long> seqByReplica = new LinkedHashMap<>();
    private final Map<String, Long> lamportClock = new LinkedHashMap<>();
    private Item head;
    private Item tail;

    /** 本地插入：指定 left/right origin（须已知；right 可 null=尾部语义） */
    public synchronized ItemId insert(String replica, ItemId originLeft, ItemId originRight, char ch) {
        if (replica == null || replica.isBlank()) {
            throw new IllegalArgumentException("副本标识不得为空");
        }
        long base = 0L;
        for (ItemId origin : new ItemId[]{originLeft, originRight}) {
            if (origin != null) {
                Item resolved = items.get(origin);
                if (resolved == null) {
                    throw new IllegalArgumentException("origin 未先到（破坏因果序）：" + origin);
                }
                base = Math.max(base, resolved.lamport);
            }
        }
        base = Math.max(base, lamportClock.getOrDefault(replica, 0L));
        long lamport = base + 1L;
        lamportClock.put(replica, lamport);
        long seq = seqByReplica.merge(replica, 1L, Long::sum);
        Item item = new Item(new ItemId(replica, seq), ch, originLeft, originRight, lamport);
        integrate(item);
        return item.id;
    }

    /** 本地追加：originLeft=当前尾部，originRight=null */
    public synchronized ItemId append(String replica, char ch) {
        ItemId leftId = tail == null ? null : tail.id;
        return insert(replica, leftId, null, ch);
    }

    /** 在指定条目之后插入（本地；右源记当时的下一活跃位） */
    public synchronized ItemId insertAfter(String replica, ItemId after, char ch) {
        if (after != null && !items.containsKey(after)) {
            throw new IllegalArgumentException("插入锚点未知：" + after);
        }
        Item anchor = after == null ? null : items.get(after);
        ItemId rightId = anchor == null
                ? (head == null ? null : head.id)
                : (anchor.next == null ? null : anchor.next.id);
        return insert(replica, after, rightId, ch);
    }

    /** 删除：墓碑标记（幂等；未知 id 拒绝） */
    public synchronized void delete(ItemId id) {
        if (id == null || !items.containsKey(id)) {
            throw new IllegalArgumentException("删除目标未知：" + id);
        }
        items.get(id).deleted = true;
    }

    /**
     * 确定性整合：自锚点向右跳过排序键更低的条目后插入——
     * （lamport，副本 id，序号）严格全序，低键恒左、高键恒右。
     */
    private void integrate(Item item) {
        if (items.containsKey(item.id)) {
            throw new IllegalArgumentException("重复条目：" + item.id);
        }
        Item left = item.originLeft == null ? null : items.get(item.originLeft);
        Item o = left == null ? head : left.next;
        while (o != null && compareKeys(o, item) < 0) {
            left = o;
            o = o.next;
        }
        linkAfter(left, item);
        items.put(item.id, item);
    }

    /** 键比较：lamport 优先，平局按副本 id 字典序，再按序号（严格全序） */
    private static int compareKeys(Item a, Item b) {
        if (a.lamport != b.lamport) {
            return Long.compare(a.lamport, b.lamport);
        }
        int byReplica = a.id.replica().compareTo(b.id.replica());
        if (byReplica != 0) {
            return byReplica;
        }
        return Long.compare(a.id.seq(), b.id.seq());
    }

    private void linkAfter(Item left, Item item) {
        if (left == null) {
            item.next = head;
            item.prev = null;
            if (head != null) {
                head.prev = item;
            }
            head = item;
            if (tail == null) {
                tail = item;
            }
        } else {
            item.prev = left;
            item.next = left.next;
            if (left.next != null) {
                left.next.prev = item;
            } else {
                tail = item;
            }
            left.next = item;
        }
    }

    /** 存活字符投影 */
    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (Item cur = head; cur != null; cur = cur.next) {
            if (!cur.deleted) {
                sb.append(cur.value);
            }
        }
        return sb.toString();
    }

    /** 条目数（含墓碑） */
    public synchronized int itemCount() {
        return items.size();
    }

    /** 尾部条目 id（测试与联动面） */
    public synchronized ItemId lastId() {
        return tail == null ? null : tail.id;
    }

    /** 条目 id 按线性序输出（收敛断言用，含墓碑） */
    public synchronized List<ItemId> order() {
        List<ItemId> out = new ArrayList<>();
        for (Item cur = head; cur != null; cur = cur.next) {
            out.add(cur.id);
        }
        return out;
    }

    /** 导出全部条目（反熵） */
    public synchronized List<ItemData> exportItems() {
        List<ItemData> out = new ArrayList<>();
        for (Item cur = head; cur != null; cur = cur.next) {
            out.add(new ItemData(cur.id, cur.value, cur.originLeft, cur.originRight,
                    cur.deleted, cur.lamport));
        }
        return out;
    }

    /** 导入条目（因果序：origin 未到则 false，由调用方重试） */
    public synchronized boolean tryImport(ItemData data) {
        if (data == null || items.containsKey(data.id())) {
            return false;
        }
        if (data.lamport() <= 0) {
            throw new IllegalArgumentException("lamport 时钟非法：" + data.id());
        }
        for (ItemId origin : new ItemId[]{data.originLeft(), data.originRight()}) {
            if (origin != null) {
                Item resolved = items.get(origin);
                if (resolved == null) {
                    return false;
                }
                if (resolved.lamport >= data.lamport()) {
                    throw new IllegalArgumentException("lamport 违反因果：" + data.id());
                }
            }
        }
        Item item = new Item(data.id(), data.value(), data.originLeft(), data.originRight(),
                data.lamport());
        item.deleted = data.deleted();
        integrate(item);
        seqByReplica.merge(data.id().replica(), data.id().seq(), Math::max);
        lamportClock.merge(data.id().replica(), data.lamport(), Math::max);
        return true;
    }
}
