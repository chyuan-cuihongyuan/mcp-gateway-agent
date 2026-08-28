package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部 MCP 挂接连接测试响应（工单 0021：连接失败可观测）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalAttachTestResponseDTO implements Serializable {

    private Boolean connected;

    /** 连接成功时的上游工具数 */
    private Integer toolCount;

    /** 连接失败原因 */
    private String error;
}
