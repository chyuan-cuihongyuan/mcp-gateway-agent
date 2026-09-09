package cn.chyuan.ai.api;

import java.util.Map;

/**
 * 治理配置导出/导入端口（工单 0077）
 *
 * @author chyuan
 */
public interface IAdminConfigService {

    /** 导出全量治理配置快照（schemaVersion 版本化；密钥只含元数据） */
    Map<String, Object> exportConfig();

    /**
     * 导入快照：先整体校验后逐对象自然键 upsert（幂等）。
     *
     * @param snapshot 导出结构的目标快照
     * @param dryRun   true=仅差异预览不落库
     * @return created/updated/unchanged 计数 + skipped/warnings 说明
     */
    Map<String, Object> importConfig(Map<String, Object> snapshot, boolean dryRun);
}
