package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.governance.adapter.repository.IWebhookEndpointRepository;
import cn.chyuan.ai.domain.governance.adapter.IWebhookHttpClient;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 治理事件 webhook 投递（工单 0051，@Primary 接管 0050 的事件端口）
 *
 * <p>事件发布 → 订阅该事件类型的启用端点异步 POST（HMAC-SHA256 签名头 + 治理台深链）；
 * 失败退避重试有限次（默认 3 次），绝不阻断主链；无启用端点零开销。
 * 消息体不含任何明文凭证（发布方口径约束）。
 *
 * @author chyuan
 */
@Slf4j
@Primary
@Service
public class WebhookEventDeliveryService implements IGovernanceEventPublisher {

    /** 投递单线程（守护；告警非关键路径，不与请求主链争抢） */
    private final ExecutorService deliveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "governance-webhook-delivery");
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private IWebhookEndpointRepository endpointRepository;

    @Resource
    private IWebhookHttpClient httpClient;

    /** 治理台基址（深链拼装） */
    @Value("${governance.webhook.admin-base-url:http://localhost:3000}")
    private String adminBaseUrl;

    /** 投递重试次数 */
    @Value("${governance.webhook.max-attempts:3}")
    private int maxAttempts;

    /** 重试退避毫秒 */
    @Value("${governance.webhook.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    @PreDestroy
    public void shutdown() {
        deliveryExecutor.shutdown();
        try {
            if (!deliveryExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                deliveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            deliveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void publish(String type, Map<String, Object> payload) {
        try {
            List<WebhookEndpointVO> endpoints = endpointRepository.findEnabled().stream()
                    .filter(ep -> ep.getEvents() == null || ep.getEvents().isEmpty()
                            || ep.getEvents().contains(type))
                    .toList();
            if (endpoints.isEmpty()) {
                return;
            }
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("eventType", type);
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("data", payload == null ? Map.of() : payload);
            envelope.put("deepLink", deepLinkOf(type, payload));
            String body = JSON.toJSONString(envelope);

            deliveryExecutor.execute(() -> deliverToAll(endpoints, type, body));
        } catch (Exception e) {
            log.warn("治理事件入队失败 type={}：{}", type, e.getMessage());
        }
    }

    private void deliverToAll(List<WebhookEndpointVO> endpoints, String type, String body) {
        for (WebhookEndpointVO endpoint : endpoints) {
            deliverWithRetry(endpoint, type, body);
        }
    }

    private void deliverWithRetry(WebhookEndpointVO endpoint, String type, String body) {
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("X-Gw-Event-Type", type);
                String timestamp = String.valueOf(System.currentTimeMillis());
                headers.put("X-Gw-Timestamp", timestamp);
                if (endpoint.getSecret() != null && !endpoint.getSecret().isBlank()) {
                    headers.put("X-Gw-Signature", hmacSha256(endpoint.getSecret(), timestamp + "." + body));
                }
                int status = httpClient.postJson(endpoint.getUrl(), headers, body);
                if (status >= 200 && status < 300) {
                    return;
                }
                log.warn("webhook 投递非 2xx endpoint={} status={} attempt={}/{}",
                        endpoint.getName(), status, attempt, maxAttempts);
            } catch (Exception e) {
                log.warn("webhook 投递失败 endpoint={} attempt={}/{}：{}",
                        endpoint.getName(), attempt, maxAttempts, e.getMessage());
            }
            try {
                Thread.sleep(retryBackoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("webhook 投递最终失败（放弃）endpoint={} type={}", endpoint.getName(), type);
    }

    /** 事件 → 治理台深链（预算类直达密钥页，其余首页） */
    private String deepLinkOf(String type, Map<String, Object> payload) {
        String base = adminBaseUrl == null || adminBaseUrl.isBlank() ? "" : adminBaseUrl.replaceAll("/$", "");
        if (type != null && type.startsWith("BUDGET_") && payload != null && payload.get("virtualKeyId") != null) {
            return base + "/keys?id=" + payload.get("virtualKeyId");
        }
        if (type != null && type.startsWith("CHANNEL_") && payload != null && payload.get("gatewayId") != null) {
            return base + "/attaches?gatewayId=" + payload.get("gatewayId");
        }
        return base + "/dashboard";
    }

    static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
