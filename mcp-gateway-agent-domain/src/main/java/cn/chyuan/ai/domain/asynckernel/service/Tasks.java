package cn.chyuan.ai.domain.asynckernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务状态机（工单 0997 EH1，tokio 思想）。
 * spawn 创建 Ready/JoinHandle 挂起完成回填/完成值一次性取/重复取拒绝。
 */
public final class Tasks {

    /** 任务状态：READY 就绪待执行 → COMPLETED 完成 / CANCELLED 取消 */
    public enum State {
        READY, COMPLETED, CANCELLED
    }

    /** JoinHandle：await 挂起等待，take 完成值一次性取 */
    public static final class JoinHandle {
        private final String taskId;
        private boolean done;
        private boolean cancelled;
        private Object value;
        private boolean taken;

        JoinHandle(String taskId) {
            this.taskId = taskId;
        }

        public String taskId() {
            return taskId;
        }

        public boolean isDone() {
            return done;
        }

        /** 挂起语义：未完成返回 null（挂起）；完成回填后返回值（只读可重复） */
        public Object await() {
            return done ? value : null;
        }

        /** 完成值一次性取：未完成拒绝/已取消拒绝/重复取拒绝 */
        public Object take() {
            if (!done) {
                throw new IllegalStateException("任务未完成: " + taskId);
            }
            if (cancelled) {
                throw new IllegalStateException("任务已取消，无完成值: " + taskId);
            }
            if (taken) {
                throw new IllegalStateException("完成值已取（一次性）: " + taskId);
            }
            taken = true;
            return value;
        }
    }

    private static final class Task {
        final String name;
        State state = State.READY;
        JoinHandle handle;

        Task(String name) {
            this.name = name;
        }
    }

    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private long seq;

    /** spawn：创建 READY 任务并登记 JoinHandle */
    public synchronized String spawn(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("任务名为空");
        }
        String id = "t" + (++seq);
        Task task = new Task(name);
        task.handle = new JoinHandle(id);
        tasks.put(id, task);
        return id;
    }

    public synchronized State state(String taskId) {
        return require(taskId).state;
    }

    public synchronized JoinHandle handle(String taskId) {
        return require(taskId).handle;
    }

    /** 完成：READY → COMPLETED 并回填 JoinHandle；未知任务/重复完成拒绝 */
    public synchronized void complete(String taskId, Object value) {
        Task task = require(taskId);
        if (task.state != State.READY) {
            throw new IllegalStateException("任务不可完成: " + taskId + "=" + task.state);
        }
        task.state = State.COMPLETED;
        task.handle.done = true;
        task.handle.value = value;
    }

    /** 取消：READY → CANCELLED；完成后取消拒绝 */
    public synchronized void cancel(String taskId) {
        Task task = require(taskId);
        if (task.state != State.READY) {
            throw new IllegalStateException("任务不可取消: " + taskId + "=" + task.state);
        }
        task.state = State.CANCELLED;
        task.handle.done = true;
        task.handle.cancelled = true;
    }

    private Task require(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }
}
