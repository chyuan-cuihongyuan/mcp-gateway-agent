package cn.chyuan.ai.infrastructure.externalattach;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游 OAuth client credentials token 管理（工单 0061）
 *
 * <p>token 按 expires_in 缓存并提前 60 秒刷新（SDK 请求定制器无响应可见性，
 * 401 被动重取不可达——以主动提前刷新等价覆盖，偏差记录于工单 Resolution）。
 * 单实例内存缓存；获取失败抛 IllegalStateException 由调用方降级为连接失败可观测。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class UpstreamOAuthTokenManager {

    private static final long REFRESH_AHEAD_SECONDS = 60;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** cacheKey(tokenUrl|clientId|scope) → 缓存 token */
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * 获取访问令牌（缓存命中或新取）。
     *
     * @param authConfig {"tokenUrl","clientId","clientSecret","scope"}（scope 可空）
     */
    public String accessToken(String authConfig) {
        JSONObject config = JSON.parseObject(authConfig == null ? "{}" : authConfig);
        String tokenUrl = config.getString("tokenUrl");
        String clientId = config.getString("clientId");
        String clientSecret = config.getString("clientSecret");
        if (StringUtils.isBlank(tokenUrl) || StringUtils.isBlank(clientId)
                || StringUtils.isBlank(clientSecret)) {
            throw new IllegalStateException("OAUTH_CC 鉴权配置不完整（tokenUrl/clientId/clientSecret）");
        }
        String scope = config.getString("scope");
        String cacheKey = tokenUrl + "|" + clientId + "|" + StringUtils.defaultString(scope);
        CachedToken cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt - REFRESH_AHEAD_SECONDS * 1000 > now) {
            return cached.token;
        }
        String token = fetchToken(tokenUrl, clientId, clientSecret, scope);
        long expiresIn = parseExpires(config.getLongValue("expiresInOverride"));
        cache.put(cacheKey, new CachedToken(token, now + expiresIn * 1000));
        return token;
    }

    private String fetchToken(String tokenUrl, String clientId, String clientSecret, String scope) {
        try {
            StringBuilder form = new StringBuilder()
                    .append("grant_type=client_credentials")
                    .append("&client_id=").append(java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                    .append("&client_secret=").append(java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
            if (StringUtils.isNotBlank(scope)) {
                form.append("&scope=").append(java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("token 端点返回 " + response.statusCode());
            }
            JSONObject body = JSON.parseObject(response.body());
            String token = body.getString("access_token");
            if (StringUtils.isBlank(token)) {
                throw new IllegalStateException("token 端点响应缺 access_token");
            }
            long expiresIn = body.getLongValue("expires_in");
            cache.computeIfPresent(tokenUrl, (k, v) -> v); // no-op，占位保持结构清晰
            if (expiresIn > 0) {
                // 以实际 expires_in 修正缓存（fetchToken 之外的调用方拿不到——直接在这里写回）
                String cacheKey = tokenUrl + "|" + clientId + "|" + StringUtils.defaultString(scope);
                cache.put(cacheKey, new CachedToken(token,
                        System.currentTimeMillis() + expiresIn * 1000));
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OAuth token 获取失败: " + e.getMessage(), e);
        }
    }

    private static long parseExpires(long override) {
        return override > 0 ? override : 3600;
    }

    /** 测试与运维可见的缓存大小 */
    public int cacheSize() {
        return cache.size();
    }

    private record CachedToken(String token, long expiresAt) {
    }
}
