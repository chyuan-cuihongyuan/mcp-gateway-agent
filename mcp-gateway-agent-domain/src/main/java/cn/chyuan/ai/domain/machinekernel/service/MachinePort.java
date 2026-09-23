package cn.chyuan.ai.domain.machinekernel.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 状态机端口+组合管线（工单 0625 BV8）。
 * MachinePort（事件输入→状态输出+副作用+快照）组合管线：
 * 长任务生命周期样例（idle→running[下载|解析]→done/error，
 * 含 after 超时与 invoke）端到端；与 session·generation 只读联动
 * （长任务状态迁移校验可选形态，泛型入参不 import 两域，不改任何类）/
 * machine-kernel.enabled 默认关（开启才改变行为）。
 */
public interface MachinePort {

    /** 长任务阶段事件 */
    String EVENT_START = "START";
    String EVENT_DOWNLOADED = "DOWNLOADED";
    String EVENT_PARSED = "PARSED";
    String EVENT_FAIL = "FAIL";
    String EVENT_TIMEOUT = "TIMEOUT";

    /** 长任务状态 */
    String STATE_IDLE = "idle";
    String STATE_RUNNING = "running";
    String STATE_DONE = "done";
    String STATE_ERROR = "error";

    /** 长任务机器样例（并行下载/解析区域 + 失败短路） */
    Statechart.Interpreter longTaskMachine(Map<String, Object> initialContext);

    /** 派发事件并返回活跃叶 */
    Set<String> send(Statechart.Interpreter machine, String event);

    /** 快照序列化（持久化形态） */
    String exportSnapshot(Statechart.Interpreter machine);

    /** 从快照恢复（继续接受事件） */
    Statechart.Interpreter restoreMachine(String serialized);

    /** 长任务状态迁移校验（session·generation 联动面：非法事件序列报告） */
    boolean isTerminal(Statechart.Interpreter machine);

    /** 内存假实现：Statechart+AfterTimers+ActorRunner 组合 */
    class InMemoryMachines implements MachinePort {

        private Statechart definition() {
            return Statechart.builder(STATE_IDLE)
                    .undefinedPolicy(Statechart.UndefinedPolicy.REJECT)
                    .state(STATE_IDLE)
                    .parallel(STATE_RUNNING, List.of("downloadRegion", "parseRegion"))
                    .finalState(STATE_DONE)
                    .finalState(STATE_ERROR)
                    .composite("downloadRegion", "downloading")
                    .composite("parseRegion", "parsing")
                    .state("downloading")
                    .state("parsing")
                    .finalState("downloadDone", "downloadRegion")
                    .finalState("parseDone", "parseRegion")
                    .onEntry(STATE_IDLE, "log:idle")
                    .onEntry(STATE_RUNNING, "log:running")
                    .onEntry(STATE_ERROR, "log:error")
                    .transition(STATE_IDLE, EVENT_START, STATE_RUNNING)
                    .transition("downloading", EVENT_DOWNLOADED, "downloadDone", null, "act:downloaded")
                    .transition("parsing", EVENT_PARSED, "parseDone", null, "act:parsed")
                    .transition("downloading", EVENT_FAIL, STATE_ERROR, null, "act:fail-short")
                    .transition("parsing", EVENT_FAIL, STATE_ERROR, null, "act:fail-short")
                    .transition("downloading", EVENT_TIMEOUT, STATE_ERROR, null, "act:timeout")
                    .transition("parsing", EVENT_TIMEOUT, STATE_ERROR, null, "act:timeout")
                    .transition(STATE_RUNNING, "done." + STATE_RUNNING, STATE_DONE, null, "act:complete")
                    .build();
        }

        @Override
        public synchronized Statechart.Interpreter longTaskMachine(Map<String, Object> initialContext) {
            Statechart.Interpreter machine = definition().start();
            machine.context().putAll(initialContext == null ? Map.of() : initialContext);
            return machine;
        }

        @Override
        public synchronized Set<String> send(Statechart.Interpreter machine, String event) {
            if (machine == null || event == null) {
                throw new IllegalArgumentException("机器与事件不得为 null");
            }
            machine.send(event);
            return machine.activeLeaves();
        }

        @Override
        public synchronized String exportSnapshot(Statechart.Interpreter machine) {
            return MachineSnapshots.serialize(machine.snapshot());
        }

        @Override
        public synchronized Statechart.Interpreter restoreMachine(String serialized) {
            Statechart.Interpreter machine = definition().start();
            machine.restore(MachineSnapshots.deserialize(serialized));
            return machine;
        }

        @Override
        public synchronized boolean isTerminal(Statechart.Interpreter machine) {
            return machine.isFinal(STATE_DONE) || machine.activeLeaves().contains(STATE_ERROR);
        }
    }
}
