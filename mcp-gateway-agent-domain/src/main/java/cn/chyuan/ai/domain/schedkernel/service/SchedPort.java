package cn.chyuan.ai.domain.schedkernel.service;

import java.util.List;

/**
 * 调度端口（工单 0739 CI8，golang GMP 思想）。
 * go/sync/step 入口统一编排/machinekernel 状态机形状只读联动（G 状态快照，泛型入参不 import）/
 * sched-kernel.enabled 默认关（开启才改变行为）。
 */
public interface SchedPort {

    /** go：注册任务并进入 P 队列 */
    long go(int pIndex, String name, List<String> steps);

    /** 调度 n 步（每步一个协作动作） */
    void step(int n);

    /** 运行至空闲（死锁抛出） */
    void runUntilIdle();

    /** WaitGroup 语义入口 */
    SchedulerWaitGroup waitGroup();

    /** channel 语义入口 */
    SchedulerChannel channel(int capacity);

    /** netpoller 注入入口（IO 完成唤醒） */
    void ioComplete(long gId);

    /** machinekernel 只读联动形态：G 状态快照（状态机形状数据） */
    List<String> gStateSnapshot();

    /** 统计快照 */
    String statsLine();

    static SchedPort inMemory(int pCount) {
        return new InMemorySched(pCount);
    }

    /** WaitGroup 句柄 */
    interface SchedulerWaitGroup {
        void add(long delta);

        void done();

        void await(long gId);

        long counter();
    }

    /** channel 句柄 */
    interface SchedulerChannel {
        void send(long gId, String value);

        String recv(long gId);

        void close();

        int buffered();
    }
}

final class InMemorySched implements SchedPort {

    private final Scheduler scheduler;
    private final java.util.Map<Long, G> gs = new java.util.LinkedHashMap<>();
    private final java.util.Map<Long, WaitGroup> waitGroups = new java.util.HashMap<>();
    private final java.util.Map<Long, Channel> channels = new java.util.HashMap<>();
    private long wgSequence;
    private long chSequence;

    InMemorySched(int pCount) {
        this.scheduler = Scheduler.of(pCount);
    }

    @Override
    public long go(int pIndex, String name, List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("任务步不可为空");
        }
        java.util.List<G.Action> actions = new java.util.ArrayList<>();
        for (String step : steps) {
            actions.add((sched, self) -> sched.recordStep(self, step));
        }
        G g = new G(name, actions);
        gs.put(g.id(), g);
        scheduler.go(pIndex, g);
        return g.id();
    }

    @Override
    public void step(int n) {
        for (int i = 0; i < n; i++) {
            scheduler.step();
        }
    }

    @Override
    public void runUntilIdle() {
        scheduler.runUntilIdle();
    }

    @Override
    public SchedulerWaitGroup waitGroup() {
        WaitGroup wg = new WaitGroup(scheduler);
        waitGroups.put(++wgSequence, wg);
        long id = wgSequence;
        return new SchedulerWaitGroup() {
            @Override
            public void add(long delta) {
                wg.add(delta);
            }

            @Override
            public void done() {
                wg.done();
            }

            @Override
            public void await(long gId) {
                wg.wait(gs.get(gId));
            }

            @Override
            public long counter() {
                return wg.counter();
            }
        };
    }

    @Override
    public SchedulerChannel channel(int capacity) {
        Channel ch = new Channel(scheduler, capacity);
        channels.put(++chSequence, ch);
        long id = chSequence;
        return new SchedulerChannel() {
            @Override
            public void send(long gId, String value) {
                ch.send(gs.get(gId), value);
            }

            @Override
            public String recv(long gId) {
                return ch.recv(gs.get(gId));
            }

            @Override
            public void close() {
                ch.close();
            }

            @Override
            public int buffered() {
                return ch.buffered();
            }
        };
    }

    @Override
    public void ioComplete(long gId) {
        G g = gs.get(gId);
        scheduler.netPoller().ioComplete(g, g.state());
    }

    @Override
    public List<String> gStateSnapshot() {
        List<String> snapshot = new java.util.ArrayList<>();
        for (G g : scheduler.allGs()) {
            snapshot.add(g.name() + ":" + g.state());
        }
        return snapshot;
    }

    @Override
    public String statsLine() {
        SchedStats stats = scheduler.stats();
        return "created=" + stats.created() + " finished=" + stats.finished()
                + " steps=" + stats.steps() + " preemptions=" + stats.preemptionCount();
    }
}
