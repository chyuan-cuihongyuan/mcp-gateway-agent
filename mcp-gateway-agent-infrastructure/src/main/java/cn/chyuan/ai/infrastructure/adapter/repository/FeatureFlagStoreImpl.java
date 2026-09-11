package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.service.FeatureFlagService;
import cn.chyuan.ai.infrastructure.dao.IFeatureFlagDao;
import cn.chyuan.ai.infrastructure.dao.po.McpFeatureFlagPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特性开关节点实现（工单 0178 Y2）— 桥接 domain FlagStore 端口与 MyBatis DAO。
 */
@Slf4j
@Component
public class FeatureFlagStoreImpl implements FeatureFlagService.FlagStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IFeatureFlagDao featureFlagDao;

    @Override
    public Boolean enabledOf(String flagKey) {
        McpFeatureFlagPO po = featureFlagDao.queryByKey(flagKey);
        return po == null ? null : po.getEnabled() != null && po.getEnabled() == 1;
    }

    @Override
    public void upsert(String flagKey, boolean enabled, String note, String operator) {
        featureFlagDao.upsert(McpFeatureFlagPO.builder()
                .flagKey(flagKey)
                .enabled(enabled ? 1 : 0)
                .note(note)
                .operator(operator)
                .updateTime(FMT.format(LocalDateTime.now()))
                .build());
    }

    @Override
    public Map<String, Boolean> loadAll() {
        List<McpFeatureFlagPO> all = featureFlagDao.queryAll();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (McpFeatureFlagPO po : all) {
            result.put(po.getFlagKey(), po.getEnabled() != null && po.getEnabled() == 1);
        }
        return result;
    }
}
