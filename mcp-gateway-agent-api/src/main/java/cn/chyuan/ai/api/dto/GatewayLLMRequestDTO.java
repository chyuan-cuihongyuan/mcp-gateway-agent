package cn.chyuan.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大模型请求测试
 *
 * @author chyuan
 *         2026/4/8 08:40
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayLLMRequestDTO {

    /**
     * 网关ID
     */
    @NotBlank(message = "网关ID不能为空")
    private String gatewayId;

    /**
     * 认证Key
     */
    @NotBlank(message = "认证Key不能为空")
    private String authApiKey;

    /**
     * 超时时间
     */
    private Integer timeout;

    /**
     * 请求信息
     */
    private String message;

    /**
     * 重新加载LLM，当有协议更新时，可以传入入参
     */
    private boolean reload = false;

}
