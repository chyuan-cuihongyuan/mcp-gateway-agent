package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Schema 校验错误值对象（AP2：JSON 路径定位 + 错误码）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SchemaErrorVO {

    /** 错误 JSON 路径（$.a.b[0] 形式） */
    private String path;

    /** 错误码：TYPE_MISMATCH / REQUIRED_MISSING / ENUM_VIOLATION / MIN_VIOLATION / MAX_VIOLATION */
    private String code;

    /** 错误消息 */
    private String message;
}
