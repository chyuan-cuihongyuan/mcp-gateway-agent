package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 调度器（工单 0734 CI3 / 0735 CI4 / 0736 CI5 / 0737 CI6，golang GMP 思想）。
 * work stealing 窃取/时间片抢占与让出/WaitGroup·channel 阻塞唤醒/死锁检测/
 * 负载均衡抽查与 netpoller 注入。
 */
public final class Scheduler {

    private final List<P> ps = new ArrayList<>();
    private final GlobalQueue global;
    private final NetPoller netPoller;
    private final SchedStats stats = new SchedStats();
    private final long tickBudget;
    private final int balanceInterval;
    private G current;
    private long tick;
    private long currentTicks;
    private long stealWins;

    public Scheduler(int pCount, GlobalQueue global, NetPoller netPoller, long tickBudget, int balanceInterval) {
        if (pCount <= 0 || tickBudget <= 0 || balanceInterval <= 0) {
            throw new IllegalArgumentException("P 数/时间片/均衡间隔须为正");
        }
        this.global = global;
        this.netPoller = netPoller;
        this.tickBudget = tickBudget;
        this.balanceInterval = balanceInterval;
        for (int i = 0; i < pCount; i++) {
            ps.add(new P(i, global, P.DEFAULT_LOCAL_CAPACITY));
        }
    }

    public static Scheduler of(int pCount) {
        return new Scheduler(pCount, new GlobalQueue(1024), new NetPoller(), 4, 8);
    }

    public long tick() {
        return tick;
    }

    public G current() {
        return current;
    }

    public List<P> ps() {
        return List.copyOf(ps);
    }

    public GlobalQueue global() {
        return global;
    }

    public SchedStats stats() {
        return stats;
    }

    public NetPoller netPoller() {
        return netPoller;
    }

    /** go：新 G 进入发起 P 的本地队列并登记 */
    public void go(int pIndex, G g) {
        track(g);
        ps.get(pIndex).submit(g);
        stats.goroutinesCreated();
    }

    /** 阻塞挂起：当前 G 转 WAITING 并登记唤醒器 */
    public void parkCurrent(Runnable wakeHook) {
        G self = current;
        if (self == null) {
            throw new IllegalStateException("无运行中 G");
        }
        self.transit(G.State.WAITING, tick);
        if (wakeHook != null) {
            wakeHook.run();
        }
        current = null;
    }

    /** 唤醒：WAITING G 回到发起 P 本地或全局队列 */
    public void ready(G g, int pIndex) {
        g.transit(G.State.RUNNABLE, tick);
        ps.get(pIndex).submit(g);
    }

    /** 调度一步：取一个 RUNNABLE G 执行一个协作步（含抢占/均衡/netpoller 检查点） */
    public boolean step() {
        checkpoint();
        if (current == null) {
            current = acquire();
            if (current == null) {
                stats.idleSpin();
                return false;
            }
            current.transit(G.State.RUNNING, tick);
            currentTicks = 0;
        }
        G g = current;
        g.runStep(this);
        stats.stepExecuted();
        currentTicks++;
        tick++;
        if (g.state() == G.State.RUNNING) {
            if (!g.hasSteps()) {
                finish(g);
            } else if (currentTicks >= tickBudget) {
                preempt(g);
            }
        } else if (g.state() == G.State.DEAD && current == g) {
            current = null;
            stats.goroutineFinished();
        }
        return true;
    }

    /** 运行至空闲或死锁 */
    public void runUntilIdle() {
        while (true) {
            boolean progressed = step();
            if (!progressed) {
                if (allWaiting()) {
                    throw new IllegalStateException("检测到死锁：全部 G 阻塞且无就绪者");
                }
                return;
            }
        }
    }

    /** 检查点：netpoller 就绪注入全局 + 周期性均衡抽查 + 全局饥饿抑制抢占 */
    private void checkpoint() {
        G ready;
        while ((ready = netPoller.pollReady()) != null) {
            ready.transit(G.State.RUNNABLE, tick);
            global.push(ready);
            stats.netPollerWakes();
        }
        if (tick > 0 && tick % balanceInterval == 0) {
            rebalance();
        }
        if (current != null && currentTicks >= 1 && !global.isEmpty() && currentTicks >= tickBudget - 1) {
            preempt(current);
            current = null;
        }
    }

    /** 均衡抽查：最重 P 上交一半给全局，空 P 从全局取 */
    public void rebalance() {
        P heaviest = ps.get(0);
        for (P p : ps) {
            if (p.localSize() > heaviest.localSize()) {
                heaviest = p;
            }
        }
        if (heaviest.localSize() > 1) {
            heaviest.stealFromTail();
            stats.rebalances();
        }
    }

    private G acquire() {
        G g = null;
        for (P p : ps) {
            g = p.next();
            if (g != null) {
                return g;
            }
        }
        return null;
    }

    private void finish(G g) {
        g.transit(G.State.DEAD, tick);
        current = null;
        stats.goroutineFinished();
    }

    /** 时间片超限让出：回队尾（RUNNING→RUNNABLE） */
    void preempt(G g) {
        g.transit(G.State.RUNNABLE, tick);
        ps.get(0).submit(g);
        current = null;
        stats.preemptions();
    }

    private boolean allWaiting() {
        if (created.isEmpty()) {
            return false;
        }
        for (G g : created) {
            if (g.state() != G.State.WAITING) {
                return false;
            }
        }
        return true;
    }

    public List<G> allGs() {
        return List.copyOf(created);
    }

    private final List<G> created = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();

    /** 协作步轨迹登记（测试与联动观测用） */
    public void recordStep(G g, String step) {
        trace.add(g.name() + ":" + step);
        stats.observeQueueDepth(global.size() + ps.stream().mapToInt(P::localSize).sum());
    }

    public List<String> trace() {
        return List.copyOf(trace);
    }

    /** 全 G 登记（供死锁检测与快照） */
    public void track(G g) {
        created.add(g);
    }

    long stealWins() {
        return stealWins;
    }

    /** 窃取：遍历他 P 队尾一半上交全局（确定性次序） */
    public int stealFrom(int fromP) {
        return ps.get(fromP).stealFromTail();
    }
}
