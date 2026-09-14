package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工具健康度值对象（AP8：滑动窗口统计 + 评级）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolHealthVO {

    /** 工具名 */
    private String toolName;

    /** 窗口内调用数 */
    private int totalCalls;

    /** 错误率（0-1，TIMEOUT/ERROR 计错误） */
    private double errorRate;

    /** P50 延迟毫秒 */
    private long p50Ms;

    /** P95 延迟毫秒 */
    private long p95Ms;

    /** 最近失败列表（时间毫秒+状态摘要） */
    private List<String> recentFailures;

    /** 评级：Healthy / Degraded / Unhealthy */
    private String grade;
}
