package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 渠道健康分快照 PO（工单 0159）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpChannelHealthSnapshotPO implements Serializable {

    private Long id;

    private Long channelId;

    private String channelName;

    private Double score;

    private Double errorRate;

    private Double probeScore;

    private Long avgLatencyMs;

    private Date sampledAt;
}
