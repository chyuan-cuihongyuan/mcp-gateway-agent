package cn.chyuan.ai.domain.asynckernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 取消令牌（工单 1003 EH7，tokio CancellationToken 思想）。
 * cancel 父传播子/子取消不传父/重复 cancel 幂等/回调注册即触发已取消。
 */
public final class CancellationToken {

    private final List<CancellationToken> children = new ArrayList<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    private CancellationToken() {
    }

    public static CancellationToken create() {
        return new CancellationToken();
    }

    /** 子令牌：父取消时级联取消 */
    public synchronized CancellationToken child() {
        CancellationToken childToken = new CancellationToken();
        children.add(childToken);
        return childToken;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** 取消：幂等；注册回调按序执行；级联传播子令牌 */
    public synchronized void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable callback : callbacks) {
            callback.run();
        }
        for (CancellationToken childToken : children) {
            childToken.cancel();
        }
    }

    /** 注册回调：令牌已取消则注册即触发，否则入列待取消时执行 */
    public synchronized void onDispose(Runnable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("回调为空");
        }
        if (cancelled) {
            callback.run();
            return;
        }
        callbacks.add(callback);
    }
}
