package cn.chyuan.ai.domain.llmchannel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 渠道健康分快照值对象（工单 0159，定时采样落库）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelHealthSnapshotVO {

    private Long id;

    /** 渠道 id（mcp_llm_channel.id） */
    private Long channelId;

    /** 渠道名（快照自描述，渠道删除后仍可读） */
    private String channelName;

    /** 综合健康分 0-100 */
    private Double score;

    /** 错误率 0-1（近 N 次账本；无数据为 null） */
    private Double errorRate;

    /** 探测分 0-100（缺探测为 null） */
    private Double probeScore;

    /** 平均延迟毫秒（无数据为 null） */
    private Long avgLatencyMs;

    private Date sampledAt;
}
