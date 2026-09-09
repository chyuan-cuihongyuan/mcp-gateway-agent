package cn.chyuan.ai.infrastructure.adapter.redis;

import cn.chyuan.ai.domain.governance.adapter.IConfigEventBus;
import cn.chyuan.ai.domain.governance.service.ConfigHotReloadService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配置热更新事件总线适配（工单 0078）
 *
 * <p>Redis pub-sub 实现：发布侧 convertAndSend；订阅侧独占单线程容器监听
 * {@link #CHANNEL}，反序列化后回调领域协调服务。Redis 未装配时整体空转
 * （退化为纯 TTL）；订阅断连由容器自带重连兜底，另有 5s 心跳日志观察消费存活。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class ConfigEventBusAdapter implements IConfigEventBus {

    /** 频道名（多实例共享） */
    static final String CHANNEL = "mcp.gateway.config.events";

    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Resource
    private ConfigHotReloadService configHotReloadService;

    private RedisMessageListenerContainer listenerContainer;
    private ScheduledExecutorService heartbeat;

    @PostConstruct
    void subscribe() {
        StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null) {
            log.info("配置热更新事件总线未装配（Redis 不可用）——缓存生效语义退化为纯 TTL");
            return;
        }
        try {
            RedisConnectionFactory factory = template.getConnectionFactory();
            if (factory == null) {
                return;
            }
            listenerContainer = new RedisMessageListenerContainer();
            listenerContainer.setConnectionFactory(factory);
            listenerContainer.setTaskExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "config-event-subscriber");
                thread.setDaemon(true);
                return thread;
            }));
            listenerContainer.addMessageListener((message, pattern) -> onMessage(message.getBody()),
                    new org.springframework.data.redis.listener.ChannelTopic(CHANNEL));
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
            heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "config-event-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeat.scheduleAtFixedRate(() -> {
                if (listenerContainer.isRunning()) {
                    log.debug("配置热更新订阅存活：{}", CHANNEL);
                }
            }, 5, 60, TimeUnit.SECONDS);
            log.info("配置热更新事件总线已订阅频道：{}", CHANNEL);
        } catch (Exception e) {
            log.warn("配置热更新订阅启动失败（退化为纯 TTL）：{}", e.getMessage());
            listenerContainer = null;
        }
    }

    @PreDestroy
    void shutdown() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        if (listenerContainer != null) {
            try {
                listenerContainer.stop();
                listenerContainer.destroy();
            } catch (Exception e) {
                log.debug("配置热更新订阅容器销毁异常（忽略）：{}", e.getMessage());
            }
        }
    }

    @Override
    public void publish(String objectType, String objectId) {
        StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("objectType", objectType);
        payload.put("objectId", objectId);
        payload.put("sourceInstanceId", configHotReloadService.getInstanceId());
        payload.put("occurredAt", System.currentTimeMillis());
        template.convertAndSend(CHANNEL, payload.toJSONString());
    }

    /** 订阅回调：反序列化 → 领域协调（自实例跳过/异常兜底在协调服务内） */
    void onMessage(byte[] body) {
        try {
            JSONObject payload = JSON.parseObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            configHotReloadService.onRemoteEvent(
                    payload.getString("objectType"),
                    payload.getString("objectId"),
                    payload.getString("sourceInstanceId"));
        } catch (Exception e) {
            log.warn("配置热更新事件处理失败（忽略，TTL 兜底）：{}", e.getMessage());
        }
    }
}
