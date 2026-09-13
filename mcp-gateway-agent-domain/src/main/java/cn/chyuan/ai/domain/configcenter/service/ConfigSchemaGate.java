package cn.chyuan.ai.domain.configcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置 schema 校验门（工单 0253 AG3）：命名空间 → schema 注册表（内存），
 * 发布链路按命名空间取 schema 校验内容；未注册 schema 的命名空间跳过（宽松默认）。
 * 校验失败由发布链路拒绝（错误码 -32026）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigSchemaGate {

    /** 发布被 schema 门拦截的错误码段（沿用 -32xxx 网关口径） */
    public static final int ERR_SCHEMA_INVALID = -32026;

    private final Map<String, String> schemas = new ConcurrentHashMap<>();

    /** 注册/更新命名空间 schema（schema 本身非法则拒绝注册） */
    public void register(String namespace, String schemaJson) {
        List<String> errors = JsonSchemaLite.validateSchema(schemaJson);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("schema 非法: " + String.join("; ", errors));
        }
        schemas.put(namespace, schemaJson);
        log.info("配置 schema 注册: ns={}", namespace);
    }

    /** 取消注册（回退为无门放行） */
    public void unregister(String namespace) {
        schemas.remove(namespace);
    }

    public String schemaOf(String namespace) {
        return schemas.get(namespace);
    }

    public boolean hasSchema(String namespace) {
        return schemas.containsKey(namespace);
    }

    /** 发布门校验：无 schema 放行返回空清单；有 schema 返回错误清单（非空即拒绝发布） */
    public List<String> check(String namespace, String contentJson) {
        String schemaJson = schemas.get(namespace);
        if (schemaJson == null) {
            return List.of();
        }
        return JsonSchemaLite.validate(contentJson, schemaJson);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(schemas);
    }
}
