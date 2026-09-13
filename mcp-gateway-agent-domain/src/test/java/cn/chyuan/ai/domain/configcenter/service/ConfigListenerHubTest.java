package cn.chyuan.ai.domain.configcenter.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置长轮询监听单测（工单 0255 AG5）：摘要稳定性/立即返回/挂起唤醒/超时。
 */
class ConfigListenerHubTest {

    @Test
    void 摘要键序无关且内容敏感() {
        String d1 = ConfigDigestCalculator.digest(Map.of("a", "1", "b", "2"));
        String d2 = ConfigDigestCalculator.digest(new java.util.TreeMap<>(Map.of("b", "2", "a", "1")));
        assertEquals(d1, d2);
        String d3 = ConfigDigestCalculator.digest(Map.of("a", "1", "b", "3"));
        assertFalse(d1.equals(d3));
        // 单内容 md5 稳定
        assertEquals(ConfigDigestCalculator.md5("x"), ConfigDigestCalculator.md5("x"));
    }

    @Test
    void 摘要不一致立即返回不挂起() {
        ConfigListenerHub hub = new ConfigListenerHub();
        hub.onChanged("ns", Map.of("k", "v"));
        hub.seedDigest("same", "x");
        long start = System.currentTimeMillis();
        ConfigListenerHub.ListenResult result = hub.listen(Map.of("ns", "stale", "same", "x"), 5_000);
        assertTrue(System.currentTimeMillis() - start < 1_000);
        assertFalse(result.timedOut());
        assertEquals(List.of("ns"), result.changedNamespaces());
        // 客户端摘要追平后无变更
        String current = ConfigDigestCalculator.digest(Map.of("k", "v"));
        ConfigListenerHub.ListenResult synced = hub.listen(Map.of("ns", current), 50);
        assertTrue(synced.timedOut());
        assertTrue(synced.changedNamespaces().isEmpty());
    }

    @Test
    void 挂起中被发布唤醒() throws Exception {
        ConfigListenerHub hub = new ConfigListenerHub();
        hub.seedDigest("ns", "stable");
        AtomicReference<ConfigListenerHub.ListenResult> captured = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            captured.set(hub.listen(Map.of("ns", "stable"), 10_000));
            done.countDown();
        });
        waiter.start();
        Thread.sleep(150);
        // 另一线程发布变更 → 唤醒
        hub.onChanged("ns", "changed");
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of("ns"), captured.get().changedNamespaces());
        assertFalse(captured.get().timedOut());
    }

    @Test
    void 无变更超时返回timedOut() {
        ConfigListenerHub hub = new ConfigListenerHub();
        hub.onChanged("ns", "d");
        long start = System.currentTimeMillis();
        ConfigListenerHub.ListenResult result = hub.listen(Map.of("ns", "d"), 200);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 150 && elapsed < 5_000);
        assertTrue(result.timedOut());
        assertTrue(result.changedNamespaces().isEmpty());
    }
}
