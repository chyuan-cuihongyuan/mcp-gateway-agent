package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用留痕值对象（AP5：谁/何时/参数摘要/结果状态/耗时）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolCallRecordVO {

    /** 留痕ID */
    private String callId;

    /** 租户 */
    private String tenantId;

    /** 工具名 */
    private String toolName;

    /** 参数摘要（脱敏截断） */
    private String paramSummary;

    /** 结果状态：SUCCESS/TIMEOUT/ERROR/TRUNCATED/QUOTA_REJECTED */
    private String status;

    /** 耗时毫秒 */
    private long costMs;

    /** token 估算 */
    private int tokenEstimate;

    /** 调用时间毫秒 */
    private long atMs;
}
