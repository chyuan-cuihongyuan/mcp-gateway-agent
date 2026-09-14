package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具注册条目值对象（AP7：tool_registry 落库形态）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegistryEntryVO {

    /** 工具名（唯一） */
    private String name;

    /** 描述 */
    private String description;

    /** 参数 schema JSON 字符串 */
    private String parameterSchemaJson;

    /** 标签集（逗号拼接） */
    private String tags;

    /** 来源 OpenAPI 文档指纹 */
    private String sourceFingerprint;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** 更新时间毫秒（应用层维护） */
    private long updatedAtMs;
}
