package cn.chyuan.ai.cases.admin.governance;

import cn.chyuan.ai.api.IAdminConfigService;
import cn.chyuan.ai.domain.governance.service.ConfigSnapshotService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 治理配置导出/导入用例（工单 0077）：直通领域快照服务
 *
 * @author chyuan
 */
@Service
public class AdminConfigService implements IAdminConfigService {

    @Resource
    private ConfigSnapshotService configSnapshotService;

    @Override
    public Map<String, Object> exportConfig() {
        return configSnapshotService.export();
    }

    @Override
    public Map<String, Object> importConfig(Map<String, Object> snapshot, boolean dryRun) {
        return configSnapshotService.importSnapshot(snapshot, dryRun);
    }
}
