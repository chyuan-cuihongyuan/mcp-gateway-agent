package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.configcenter.service.DriftDetector;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 环境属性运行时配置供给（工单 0258 AG8）：漂移检测的"实际值"从网关自身 Spring 环境
 * 读取——命名空间按 {@code config.center.drift.mapping.<ns>=<属性前缀>} 映射
 * （前缀内 `.` 会在属性键里体现，如 mapping.ratelimit=governance.ratelimit），无映射的
 * 命名空间返回空 Map（全部 MISSING，即视为未接管运行时）。
 *
 * @author chyuan
 */
@Component
public class EnvironmentRuntimeConfig implements DriftDetector.RuntimeConfigPort {

    private final ConfigurableEnvironment environment;

    public EnvironmentRuntimeConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public Map<String, String> actualFlat(String namespace) {
        Map<String, String> out = new HashMap<>();
        String prefix = environment.getProperty("config.center.drift.mapping." + namespace);
        if (prefix == null || prefix.isBlank()) {
            return out;
        }
        String dotted = prefix.endsWith(".") ? prefix : prefix + ".";
        for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String key : enumerable.getPropertyNames()) {
                    if (key.startsWith(dotted)) {
                        String value = environment.getProperty(key);
                        if (value != null) {
                            out.put(key.substring(dotted.length()), value);
                        }
                    }
                }
            }
        }
        return out;
    }
}
