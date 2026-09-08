package cn.chyuan.ai.infrastructure.externalattach;

import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游鉴权头解析（工单 0061：NONE/HEADER/BEARER/OAUTH_CC 四类型分派）
 *
 * <p>产出每次上游请求需注入的 HTTP 头；OAUTH_CC 经
 * {@link UpstreamOAuthTokenManager} 缓存取 token；解析失败抛 IllegalStateException
 * （上游连接失败可观测口径）。存量 apiKey 字段在 BEARER 类型下兜底（normalize 已按
 * apiKey 有无推导 authType，行为等价迁移）。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class UpstreamAuthHeaders {

    private final UpstreamOAuthTokenManager tokenManager;

    public UpstreamAuthHeaders(UpstreamOAuthTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    /**
     * 解析上游请求注入头。
     *
     * @return 空 Map = 无需注入
     */
    public Map<String, String> headersFor(ExternalAttachVO config) {
        Map<String, String> headers = new LinkedHashMap<>();
        // 直连构造的存量 VO（未经 admin normalize）无 authType 但带 apiKey：按 BEARER 等价处理
        String type = config.getAuthType() == null || config.getAuthType().isBlank()
                ? (StringUtils.isNotBlank(config.getApiKey())
                        ? ExternalAttachVO.AUTH_TYPE_BEARER : ExternalAttachVO.AUTH_TYPE_NONE)
                : config.getAuthType();
        JSONObject authConfig = parse(config.getAuthConfig());
        switch (type) {
            case ExternalAttachVO.AUTH_TYPE_BEARER -> {
                String token = firstNonBlank(authConfig.getString("token"), config.getApiKey());
                if (StringUtils.isNotBlank(token)) {
                    headers.put("Authorization", "Bearer " + token);
                }
            }
            case ExternalAttachVO.AUTH_TYPE_HEADER -> {
                String name = authConfig.getString("name");
                String value = authConfig.getString("value");
                if (StringUtils.isNotBlank(name) && value != null) {
                    headers.put(name, value);
                } else {
                    throw new IllegalStateException("HEADER 鉴权配置不完整（name/value）");
                }
            }
            case ExternalAttachVO.AUTH_TYPE_OAUTH_CC -> headers.put("Authorization",
                    "Bearer " + tokenManager.accessToken(config.getAuthConfig()));
            case ExternalAttachVO.AUTH_TYPE_NONE -> {
                // 不注入
            }
            default -> log.warn("未知鉴权类型（不注入）: {}", type);
        }
        return headers;
    }

    private static JSONObject parse(String authConfig) {
        if (StringUtils.isBlank(authConfig)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(authConfig);
        } catch (Exception e) {
            throw new IllegalStateException("authConfig 非合法 JSON");
        }
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
    }
}
