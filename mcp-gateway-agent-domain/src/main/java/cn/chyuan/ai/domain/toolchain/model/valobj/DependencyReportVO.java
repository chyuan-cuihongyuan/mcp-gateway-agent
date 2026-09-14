package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 依赖图报告值对象（AP6：拓扑序 + 环检测）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DependencyReportVO {

    /** 拓扑序（Kahn 稳定序：同层按名称序；有环时为部分序） */
    private List<String> topologicalOrder;

    /** 是否存在环 */
    private boolean cyclic;

    /** 环路径（如 a -> b -> a，无环为空） */
    private List<String> cyclePath;

    /** 自动补节点的缺失依赖（依赖名 → 依赖它的工具列表） */
    private List<String> missingDependencies;
}
