package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具注册条目值对象（AP1/AP7：operationId → 工具定义 + 参数 schema）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolDefinitionVO {

    /** 工具名（operationId 或派生） */
    private String name;

    /** 描述 */
    private String description;

    /** HTTP 方法 */
    private String method;

    /** 路径模板 */
    private String path;

    /** 参数 JSON Schema（object 形态） */
    private Map<String, Object> parameterSchema;

    /** 标签集 */
    private java.util.List<String> tags;

    /** 来源 OpenAPI 文档指纹 */
    private String sourceFingerprint;
}
