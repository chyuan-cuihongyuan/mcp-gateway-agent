package cn.chyuan.ai.domain.configcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置长轮询监听中枢（工单 0255 AG5）—
 * 客户端携各命名空间本地摘要挂起等待；任一命名空间发布/回滚（onChanged）即唤醒，
 * 摘要不一致的命名空间立即返回；超时无变更返回空清单。挂起上限受 listen 上限钳制。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigListenerHub {

    /** 单次长轮询挂起上限（毫秒） */
    public static final long MAX_SUSPEND_MS = 30_000;

    /** 长轮询结果 */
    public record ListenResult(List<String> changedNamespaces, boolean timedOut) {
    }

    private final Object monitor = new Object();
    /** 发布/回滚推进的变更序号（唤醒判定） */
    private long changeSeq = 0;
    /** 命名空间 → 最近内容摘要（网关侧权威值） */
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    /** 更新命名空间摘要并唤醒挂起中的监听（发布/回滚后调用） */
    public void onChanged(String namespace, String digest) {
        synchronized (monitor) {
            digests.put(namespace, digest);
            changeSeq++;
            monitor.notifyAll();
        }
    }

    /** 直接喂入配置集计算摘要（编排便利入口） */
    public void onChanged(String namespace, Map<String, String> keyContents) {
        onChanged(namespace, ConfigDigestCalculator.digest(keyContents));
    }

    /** 注册网关侧权威摘要（启动/发布时同步，不唤醒既有等待——内容未变的场景） */
    public void seedDigest(String namespace, String digest) {
        synchronized (monitor) {
            digests.put(namespace, digest);
        }
    }

    /**
     * 长轮询：比对 clientDigests 与网关侧摘要，不一致的命名空间立即返回；
     * 全一致则挂起至超时。timeoutMs 钳制到 [0, MAX_SUSPEND_MS]。
     */
    public ListenResult listen(Map<String, String> clientDigests, long timeoutMs) {
        long effective = Math.min(Math.max(timeoutMs, 0), MAX_SUSPEND_MS);
        List<String> changed = diff(clientDigests);
        if (!changed.isEmpty()) {
            return new ListenResult(List.copyOf(changed), false);
        }
        long deadline = System.currentTimeMillis() + effective;
        synchronized (monitor) {
            long startSeq = changeSeq;
            while (true) {
                changed = diff(clientDigests);
                if (!changed.isEmpty()) {
                    return new ListenResult(List.copyOf(changed), false);
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return new ListenResult(List.of(), true);
                }
                try {
                    monitor.wait(Math.min(remaining, 1_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ListenResult(List.of(), true);
                }
                // 无人发布时 changeSeq 不变，继续等；发布后循环首行重比摘要
                if (changeSeq == startSeq) {
                    continue;
                }
            }
        }
    }

    private List<String> diff(Map<String, String> clientDigests) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : clientDigests.entrySet()) {
            String local = digests.get(entry.getKey());
            if (local == null || !local.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        changed.sort(String::compareTo);
        return changed;
    }
}
