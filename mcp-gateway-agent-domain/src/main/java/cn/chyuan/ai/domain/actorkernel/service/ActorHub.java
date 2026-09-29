package cn.chyuan.ai.domain.actorkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.chyuan.ai.domain.actorkernel.service.Actors.Actor;

/**
 * 虚拟Actor编排实现（工单 1012 EI8，dapr 思想）。
 * 组合类型激活/turn 串行/定时器/提醒/空闲失活/调用环/故障策略；
 * schedkernel 队列形状只读联动：inbox 排队形状串（形状数据不 import schedkernel）。
 */
public final class ActorHub implements ActorPort {

    private final Actors actors = new Actors();
    private final Map<String, TurnQueue> inboxes = new LinkedHashMap<>();
    private final Map<String, Object> currentTurns = new HashMap<>();
    private final ActorTimers timers = new ActorTimers();
    private final Reminders reminders = new Reminders();
    private final IdleReaper idle = new IdleReaper();
    private final ActorCalls calls = new ActorCalls();
    private final FaultPolicy faults = new FaultPolicy();

    private String key(String type, String id) {
        actors.requireType(type);
        return type + "/" + id;
    }

    @Override
    public void registerType(String type) {
        actors.registerType(type);
    }

    @Override
    public String activate(String type, String id) {
        Actor actor = actors.activate(type, id);
        String actorKey = actor.key();
        inboxes.computeIfAbsent(actorKey, k -> new TurnQueue());
        idle.touch(actorKey);
        return actorKey;
    }

    @Override
    public void deactivate(String type, String id) {
        String actorKey = key(type, id);
        TurnQueue queue = requireInbox(actorKey);
        if (queue.pending() > 0) {
            throw new IllegalStateException("失活拒绝：inbox 仍有 " + queue.pending() + " 条消息");
        }
        actors.deactivate(type, id);
        inboxes.remove(actorKey);
        currentTurns.remove(actorKey);
        timers.cancelAll(actorKey);
        idle.remove(actorKey);
        faults.clear(actorKey);
    }

    @Override
    public boolean isActive(String type, String id) {
        return actors.isActive(type, id);
    }

    @Override
    public Object getState(String type, String id, String stateKey) {
        return actors.get(type, id).getState(stateKey);
    }

    @Override
    public void send(String type, String id, Object message) {
        String actorKey = activate(type, id);
        inboxes.get(actorKey).send(message);
    }

    @Override
    public boolean beginTurn(String type, String id) {
        String actorKey = key(type, id);
        TurnQueue queue = requireInbox(actorKey);
        Object message = queue.beginTurn();
        if (message == null) {
            return false;
        }
        currentTurns.put(actorKey, message);
        return true;
    }

    @Override
    public Object currentTurn(String type, String id) {
        return currentTurns.get(key(type, id));
    }

    @Override
    public void endTurn(String type, String id, boolean success) {
        String actorKey = key(type, id);
        requireInbox(actorKey).endTurn();
        currentTurns.remove(actorKey);
        idle.touch(actorKey);
        if (success) {
            faults.recordSuccess(actorKey);
        } else {
            faults.recordFailure(actorKey);
        }
    }

    @Override
    public void registerTimer(String type, String id, String name, long periodTicks, boolean repeat, Runnable action) {
        timers.register(key(type, id), name, periodTicks, repeat, action);
    }

    @Override
    public void registerReminder(String type, String id, String name, long periodTicks, boolean repeat) {
        reminders.register(key(type, id), name, periodTicks, repeat);
    }

    @Override
    public List<String> replayReminders(String type, String id) {
        return reminders.replay(key(type, id));
    }

    @Override
    public void tick() {
        timers.step();
        reminders.step();
        idle.tick();
    }

    @Override
    public List<String> reapIdle(long threshold) {
        List<String> evicted = new ArrayList<>();
        for (String actorKey : idle.reap(threshold)) {
            int slash = actorKey.indexOf('/');
            String type = actorKey.substring(0, slash);
            String id = actorKey.substring(slash + 1);
            if (actors.isActive(type, id)) {
                if (requireInbox(actorKey).pending() > 0) {
                    idle.touch(actorKey);
                    continue;
                }
                actors.deactivate(type, id);
                inboxes.remove(actorKey);
                timers.cancelAll(actorKey);
                faults.clear(actorKey);
                evicted.add(actorKey);
            }
        }
        return evicted;
    }

    @Override
    public void enterCall(String from, String to) {
        calls.enter(from, to);
    }

    @Override
    public void exitCall() {
        calls.exit();
    }

    @Override
    public int callDepth() {
        return calls.depth();
    }

    @Override
    public String queueShape(String type, String id) {
        String actorKey = key(type, id);
        TurnQueue queue = inboxes.get(actorKey);
        int pending = queue == null ? 0 : queue.pending();
        return "actor[" + actorKey + "]inbox=" + pending;
    }

    private TurnQueue requireInbox(String actorKey) {
        TurnQueue queue = inboxes.get(actorKey);
        if (queue == null) {
            throw new IllegalStateException("actor 未激活: " + actorKey);
        }
        return queue;
    }
}
