package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 反熵合并收敛（工单 0608 BT7，yjs 收敛不变量思想）。
 * 交换律 merge(a,b)=merge(b,a)/幂等 merge(a,a)=a/结合律；
 * 随机种子驱动的多副本反熵收敛测试（所有 CRDT 类型收敛到相同状态）。
 */
public final class ConvergenceKit {

    private ConvergenceKit() {
    }

    /** 随机脚本：多副本各做随机操作后随机序交换，断言全副本收敛 */
    public static final class ScriptedRun {

        public final List<GCounter> counters = new ArrayList<>();
        public final List<Registers.LwwRegister> lwws = new ArrayList<>();
        public final List<GrowSets.TwoPSet> twoPSets = new ArrayList<>();
        public final List<OrSet> orSets = new ArrayList<>();
        public final List<YataText> texts = new ArrayList<>();

        ScriptedRun(int replicas) {
            for (int i = 0; i < replicas; i++) {
                counters.add(GCounter.growOnly());
                lwws.add(new Registers.LwwRegister("", 0, "r" + i));
                twoPSets.add(new GrowSets.TwoPSet());
                orSets.add(new OrSet());
                texts.add(new YataText());
            }
        }
    }

    /** 随机操作脚本执行（固定种子确定性） */
    public static ScriptedRun runScript(int replicas, int opsPerReplica, long seed) {
        if (replicas < 2 || opsPerReplica <= 0) {
            throw new IllegalArgumentException("副本数至少 2，操作数为正");
        }
        Random random = new Random(seed);
        ScriptedRun run = new ScriptedRun(replicas);
        for (int r = 0; r < replicas; r++) {
            String replica = "r" + r;
            for (int op = 0; op < opsPerReplica; op++) {
                run.counters.get(r).increment(replica, 1 + random.nextInt(3));
                run.lwws.get(r).write("v-" + r + "-" + op, 1_000L + op, replica);
                run.twoPSets.get(r).add("elem-" + random.nextInt(6));
                run.orSets.get(r).add("tagged-" + random.nextInt(4), replica);
                run.texts.get(r).append(replica, (char) ('a' + random.nextInt(26)));
                if (random.nextInt(4) == 0) {
                    run.twoPSets.get(r).remove("elem-" + random.nextInt(6));
                    run.orSets.get(r).remove("tagged-" + random.nextInt(4));
                }
            }
        }
        return run;
    }

    /** 随机序反熵（gossip 轮次直到稳定），返回轮次 */
    public static int antiEntropy(ScriptedRun run, long seed) {
        Random random = new Random(seed);
        int rounds = 0;
        for (int gossip = 0; gossip < 32; gossip++) {
            boolean changed = false;
            int n = run.counters.size();
            for (int pair = 0; pair < n; pair++) {
                int a = random.nextInt(n);
                int b = random.nextInt(n);
                if (a == b) {
                    continue;
                }
                long ca = run.counters.get(a).value();
                run.counters.get(a).merge(run.counters.get(b));
                run.counters.get(b).merge(run.counters.get(a));
                changed |= run.counters.get(a).value() != ca;

                Registers.LwwRegister la = run.lwws.get(a);
                Registers.LwwRegister lb = run.lwws.get(b);
                String va = la.value();
                la.merge(lb);
                lb.merge(la);
                changed |= !la.value().equals(va);

                GrowSets.TwoPSet sa = run.twoPSets.get(a);
                GrowSets.TwoPSet sb = run.twoPSets.get(b);
                int liveA = sa.live().size();
                sa.merge(sb);
                sb.merge(sa);
                changed |= sa.live().size() != liveA;

                OrSet oa = run.orSets.get(a);
                OrSet ob = run.orSets.get(b);
                int oLiveA = oa.live().size();
                oa.merge(ob);
                ob.merge(oa);
                changed |= oa.live().size() != oLiveA;

                YataText ta = run.texts.get(a);
                YataText tb = run.texts.get(b);
                changed |= exchangeYata(ta, tb);
                changed |= exchangeYata(tb, ta);
            }
            rounds++;
            if (!changed) {
                break;
            }
        }
        return rounds;
    }

    /** YATA 双向交换（拓扑序重试直到全部导入或一轮无进展） */
    static boolean exchangeYata(YataText from, YataText to) {
        return exchangeYataFromList(to, from.exportItems());
    }

    /** 把条目列表拓扑序导入目标（origin 未到则重试，直到全部导入或无进展） */
    static boolean exchangeYataFromList(YataText to, List<YataText.ItemData> items) {
        boolean changed = false;
        List<YataText.ItemData> pending = new ArrayList<>(items);
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            List<YataText.ItemData> remaining = new ArrayList<>();
            for (YataText.ItemData data : pending) {
                if (to.tryImport(data)) {
                    changed = true;
                    progressed = true;
                } else {
                    remaining.add(data);
                }
            }
            pending = remaining;
        }
        return changed;
    }

    /** 收敛断言（全副本状态一致），不一致返回 false */
    public static boolean converged(ScriptedRun run) {
        long counterValue = run.counters.get(0).value();
        String lwwValue = run.lwws.get(0).value();
        var twoPLive = run.twoPSets.get(0).live();
        var orLive = run.orSets.get(0).live();
        List<YataText.ItemId> order = run.texts.get(0).order();
        String text = run.texts.get(0).text();
        for (int i = 1; i < run.counters.size(); i++) {
            if (run.counters.get(i).value() != counterValue
                    || !run.lwws.get(i).value().equals(lwwValue)
                    || !run.twoPSets.get(i).live().equals(twoPLive)
                    || !run.orSets.get(i).live().equals(orLive)
                    || !run.texts.get(i).order().equals(order)
                    || !run.texts.get(i).text().equals(text)) {
                return false;
            }
        }
        return true;
    }

    /** 幂等断言辅助：二次合并不变（计数器口径） */
    public static boolean idempotentMerge(GCounter counter) {
        Map<String, Long> before = counter.state();
        GCounter mirror = GCounter.growOnly();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            for (long i = 0; i < e.getValue(); i++) {
                mirror.increment(e.getKey(), 1);
            }
        }
        counter.merge(mirror);
        return counter.state().equals(before);
    }

    /** 交换律断言辅助（2P-Set 口径） */
    public static boolean commutativeMerge(GrowSets.TwoPSet a, GrowSets.TwoPSet b) {
        GrowSets.TwoPSet ab = new GrowSets.TwoPSet();
        ab.merge(a);
        ab.merge(b);
        GrowSets.TwoPSet ba = new GrowSets.TwoPSet();
        ba.merge(b);
        ba.merge(a);
        return ab.live().equals(ba.live());
    }

    /** 乱序导入断言辅助（YATA 收敛口径） */
    public static boolean yataConvergesUnderShuffle(List<YataText.ItemData> items, long seed) {
        YataText ordered = new YataText();
        for (YataText.ItemData data : items) {
            ordered.tryImport(data);
        }
        List<YataText.ItemData> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, new Random(seed));
        YataText target = new YataText();
        boolean progressed = true;
        List<YataText.ItemData> pending = shuffled;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            List<YataText.ItemData> remaining = new ArrayList<>();
            for (YataText.ItemData data : pending) {
                if (target.tryImport(data)) {
                    progressed = true;
                } else {
                    remaining.add(data);
                }
            }
            pending = remaining;
        }
        return pending.isEmpty() && target.order().equals(ordered.order());
    }
}
