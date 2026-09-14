package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAPI 导入报告值对象（AP1：新增/跳过/冲突清单）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ImportReportVO {

    /** 成功导入的工具名 */
    private List<String> imported;

    /** 跳过（同指纹已注册） */
    private List<String> skipped;

    /** 冲突（operationId 重复且定义不同） */
    private List<String> conflicts;

    /** 解析失败原因（文档非法时非空） */
    private String error;

    /** 是否整体成功 */
    private boolean success;
}
