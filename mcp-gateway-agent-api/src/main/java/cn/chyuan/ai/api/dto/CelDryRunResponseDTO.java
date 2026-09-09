package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * CEL 假想上下文试跑结果（工单 0076）
 *
 * @author chyuan
 */
@Data
public class CelDryRunResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** PASS / DENIED / COMPILE_ERROR / RUNTIME_ERROR */
    private String outcome;

    /** 正常求值时：true=放行 false=拒绝；故障形态恒 false */
    private Boolean allowed;

    /** 故障形态的错误定位信息（正常求值为 null） */
    private String detail;
}
