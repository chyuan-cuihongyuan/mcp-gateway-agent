package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.LoginCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.VirtualKeyCommandEntity;
import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import cn.chyuan.ai.types.util.KeyHashUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟密钥管理服务（工单 0017 / 0011 决议）
 *
 * <p>CRUD 均写审计日志（0011 决策：变更留痕）；
 * 变更后即时失效认证缓存（同实例写路径失效，跨实例最迟 30s TTL）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class VirtualKeyService implements IVirtualKeyService {

    private static final String RESOURCE_TYPE = "VIRTUAL_KEY";

    @Resource
    private IVirtualKeyRepository repository;

    @Resource
    private GovernanceAuthService governanceAuthService;

    @Resource
    private IAuditService auditService;

    /** 懒解析：切片测试上下文可能未装配热更新协调（此时仅本地失效） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<ConfigHotReloadService> configHotReloadServiceProvider;

    @Override
    public VirtualKeyVO create(VirtualKeyCommandEntity command) {
        String credential = KeyHashUtil.generateVirtualKey();
        String hash = KeyHashUtil.sha256Hex(credential);

        VirtualKeyVO vo = VirtualKeyVO.builder()
                .keyName(command.getKeyName())
                .ownerUserId(command.getOwnerUserId())
                .tenantId(command.getTenantId())
                .status("ACTIVE")
                .expiresAt(command.getExpiresAt())
                .ipAllowList(command.getIpAllowList())
                .budgetSoft(command.getBudgetSoft())
                .budgetHard(command.getBudgetHard())
                .budgetDurationHours(command.getBudgetDurationHours())
                .budgetWindowType(normalizeWindowType(command.getBudgetWindowType()))
                .costSoftLimit(command.getCostSoftLimit())
                .costHardLimit(command.getCostHardLimit())
                .skipGuardrailAllowed(command.getSkipGuardrailAllowed())
                .allowedModels(normalizeAllowedModels(command.getAllowedModels()))
                .rpmLimit(command.getRpmLimit())
                .dailyRequestLimit(command.getDailyRequestLimit())
                .dailyToolCallLimit(command.getDailyToolCallLimit())
                .tpmLimit(command.getTpmLimit())
                .dailyCostLimit(command.getDailyCostLimit())
                .build();

        VirtualKeyVO saved = repository.insert(hash, vo);
        saved.setPlaintextOnce(credential);
        saved.setMaskedKey(KeyHashUtil.mask(credential));

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("CREATE_KEY")
                .resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(saved.getId()))
                .afterJson(snapshot(saved))
                .build());

        log.info("创建虚拟密钥 id:{} name:{}", saved.getId(), saved.getKeyName());
        return saved;
    }

    @Override
    public VirtualKeyVO update(VirtualKeyCommandEntity command) {
        VirtualKeyVO existing = requireKey(command.getId());
        VirtualKeyVO vo = VirtualKeyVO.builder()
                .id(command.getId())
                .keyName(command.getKeyName())
                .ownerUserId(command.getOwnerUserId())
                .tenantId(command.getTenantId())
                .status(existing.getStatus())
                .expiresAt(command.getExpiresAt())
                .ipAllowList(command.getIpAllowList())
                .budgetSoft(command.getBudgetSoft())
                .budgetHard(command.getBudgetHard())
                .budgetDurationHours(command.getBudgetDurationHours())
                .budgetWindowType(normalizeWindowType(command.getBudgetWindowType()))
                .costSoftLimit(command.getCostSoftLimit())
                .costHardLimit(command.getCostHardLimit())
                .skipGuardrailAllowed(command.getSkipGuardrailAllowed())
                .allowedModels(normalizeAllowedModels(command.getAllowedModels()))
                .rpmLimit(command.getRpmLimit())
                .dailyRequestLimit(command.getDailyRequestLimit())
                .dailyToolCallLimit(command.getDailyToolCallLimit())
                .tpmLimit(command.getTpmLimit())
                .dailyCostLimit(command.getDailyCostLimit())
                .build();

        repository.updateMeta(command.getId(), vo);

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("UPDATE_KEY")
                .resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(command.getId()))
                .beforeJson(snapshot(existing))
                .afterJson(snapshot(vo))
                .build());

        return getById(command.getId());
    }

    @Override
    public void revoke(Long id) {
        VirtualKeyVO existing = requireKey(id);
        repository.updateStatus(id, "REVOKED");

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("REVOKE_KEY")
                .resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(id))
                .beforeJson(snapshot(existing))
                .build());

        // 认证缓存兜底失效（状态变更影响全部缓存副本）
        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    @Override
    public VirtualKeyVO regenerate(Long id) {
        VirtualKeyVO existing = requireKey(id);
        if (!"ACTIVE".equals(existing.getStatus())) {
            throw new AppException(McpErrorCodes.KEY_DISABLED, "仅启用态密钥可轮换，当前：" + existing.getStatus());
        }

        String credential = KeyHashUtil.generateVirtualKey();
        String newHash = KeyHashUtil.sha256Hex(credential);
        Date graceUntil = new Date(System.currentTimeMillis() + rotationGraceHours * 3600_000L);
        repository.rotateKey(id, newHash, graceUntil);
        governanceAuthService.invalidateAll();
        notifyKeyChange();

        VirtualKeyVO saved = getById(id);
        saved.setPlaintextOnce(credential);
        saved.setMaskedKey(KeyHashUtil.mask(credential));

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("ROTATE_KEY")
                .resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(id))
                .beforeJson(snapshot(existing))
                .afterJson("{\"rotationCount\":" + (existing.getRotationCount() == null
                        ? 1 : existing.getRotationCount() + 1) + "}")
                .build());

        log.info("虚拟密钥已轮换 id:{} name:{} 第{}代", id, existing.getKeyName(),
                existing.getRotationCount() == null ? 1 : existing.getRotationCount() + 1);
        return saved;
    }


    // ---- 工单 0052：密钥管理 API 完备化 ----

    @Override
    public void block(Long id) {
        VirtualKeyVO existing = requireKey(id);
        repository.updateStatus(id, "DISABLED");
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action("BLOCK_KEY").resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(id)).beforeJson(snapshot(existing)).build());
        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    @Override
    public void unblock(Long id) {
        VirtualKeyVO existing = requireKey(id);
        repository.updateStatus(id, "ACTIVE");
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action("UNBLOCK_KEY").resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(id)).beforeJson(snapshot(existing)).build());
        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    @Override
    public int bulkUpdateStatus(java.util.List<Long> ids, boolean block) {
        int changed = 0;
        for (Long id : ids == null ? java.util.List.<Long>of() : ids) {
            try {
                if (block) {
                    block(id);
                } else {
                    unblock(id);
                }
                changed++;
            } catch (AppException e) {
                log.warn("批量{}跳过 keyId={}：{}", block ? "禁用" : "解禁", id, e.getInfo());
            }
        }
        return changed;
    }

    @Override
    public void applyTempBudget(Long id, long increase, Date expiresAt) {
        VirtualKeyVO existing = requireKey(id);
        if (existing.getBudgetHard() == null || existing.getBudgetHard() <= 0) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "临时提额仅适用于已配置预算的密钥，请先设置 budgetHard");
        }
        if (increase <= 0) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "临时提额增量必须大于 0");
        }
        if (expiresAt == null || !expiresAt.after(new Date())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "临时提额到期时间必须晚于当前时间");
        }
        repository.applyTempBudget(id, increase, expiresAt);
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action("TEMP_BUDGET").resourceType(RESOURCE_TYPE)
                .resourceId(String.valueOf(id))
                .afterJson("{\"increase\":" + increase + ",\"expiresAt\":" + expiresAt.getTime() + "}")
                .build());
        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    /** 轮换宽限期（小时），工单 0049 */
    @Value("${governance.key.rotation-grace-hours:24}")
    private long rotationGraceHours;

    @Override
    public void grant(Long id, String gatewayId) {
        requireKey(id);
        if (!repository.existsGrant(id, gatewayId)) {
            repository.insertGrant(id, gatewayId);
        }

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("GRANT")
                .resourceType("GRANT")
                .resourceId(id + ":" + gatewayId)
                .afterJson("{\"gatewayId\":\"" + gatewayId + "\"}")
                .build());

        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    @Override
    public void revokeGrant(Long id, String gatewayId) {
        requireKey(id);
        repository.deleteGrant(id, gatewayId);

        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action("REVOKE_GRANT")
                .resourceType("GRANT")
                .resourceId(id + ":" + gatewayId)
                .beforeJson("{\"gatewayId\":\"" + gatewayId + "\"}")
                .build());

        governanceAuthService.invalidateAll();
        notifyKeyChange();
    }

    @Override
    public VirtualKeyVO getById(Long id) {
        VirtualKeyVO vo = repository.findById(id);
        if (vo != null) {
            vo.setMaskedKey("id-" + id + "/****");
            deriveStatus(vo);
        }
        return vo;
    }

    @Override
    public List<String> getGrants(Long id) {
        return repository.queryGrants(id);
    }

    @Override
    public List<VirtualKeyVO> page(String keyword, int page, int size) {
        List<VirtualKeyVO> list = repository.queryPage(keyword, Math.max(page - 1, 0) * size, size);
        list.forEach(vo -> {
            vo.setMaskedKey("id-" + vo.getId() + "/****");
            deriveStatus(vo);
        });
        return list;
    }

    /**
     * 派生状态四态（工单 0045）：落库状态 ACTIVE/DISABLED/REVOKED 之上，
     * ACTIVE 且已过期 → EXPIRED；QUOTA_EXHAUSTED 随预算票（0050）扩展。
     */
    private void deriveStatus(VirtualKeyVO vo) {
        String status = vo.getStatus();
        if (!"ACTIVE".equals(status)) {
            vo.setDerivedStatus(status);
            return;
        }
        if (vo.getExpiresAt() != null && new java.util.Date().after(vo.getExpiresAt())) {
            vo.setDerivedStatus("EXPIRED");
            return;
        }
        if (vo.getBudgetHard() != null && vo.getBudgetHard() > 0
                && vo.getBudgetUsed() != null && vo.getBudgetUsed() >= vo.getBudgetHard()) {
            vo.setDerivedStatus("QUOTA_EXHAUSTED");
            return;
        }
        vo.setDerivedStatus("ACTIVE");
    }

    @Override
    public long count(String keyword) {
        return repository.count(keyword);
    }

    @Override
    public int migrateLegacyKeys() {
        List<IVirtualKeyRepository.LegacyAuthRecord> records = repository.queryLegacyAuthRecords();
        int migrated = 0;
        for (IVirtualKeyRepository.LegacyAuthRecord record : records) {
            if (record.apiKey() == null || record.apiKey().isBlank()) {
                continue;
            }
            String hash = KeyHashUtil.sha256Hex(record.apiKey());
            // 每小时限次 → RPM 换算（向上取整，等价或略收紧）
            Integer rpm = record.rateLimitPerHour() == null ? null
                    : (int) Math.ceil(record.rateLimitPerHour() / 60.0);
            String status = record.status() != null && record.status() == 1 ? "ACTIVE" : "DISABLED";
            String keyName = "migrated:" + record.gatewayId() + ":" + KeyHashUtil.mask(record.apiKey());

            int n = repository.migrateLegacy(hash, keyName, status, rpm, record.expireTime(), record.gatewayId());
            migrated += n;
        }
        if (migrated > 0) {
            auditService.record(AuditCommandEntity.builder()
                    .actor("system")
                    .action("MIGRATE")
                    .resourceType(RESOURCE_TYPE)
                    .resourceId("legacy-gw-keys")
                    .afterJson("{\"migrated\":" + migrated + ",\"total\":" + records.size() + "}")
                    .build());
            log.info("存量 gw- 密钥等价迁移完成：本次迁移 {} 条 / 扫描 {} 条", migrated, records.size());
        }
        return migrated;
    }

    private VirtualKeyVO requireKey(Long id) {
        VirtualKeyVO vo = repository.findById(id);
        if (vo == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "虚拟密钥不存在：" + id);
        }
        return vo;
    }

    /** 审计快照（脱敏：不含哈希） */
    private String snapshot(VirtualKeyVO vo) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", vo.getId());
        map.put("keyName", vo.getKeyName());
        map.put("status", vo.getStatus());
        map.put("expiresAt", vo.getExpiresAt() == null ? null : vo.getExpiresAt().getTime());
        map.put("ipAllowList", vo.getIpAllowList());
        map.put("allowedModels", vo.getAllowedModels());
        map.put("rpmLimit", vo.getRpmLimit());
        map.put("dailyRequestLimit", vo.getDailyRequestLimit());
        map.put("dailyToolCallLimit", vo.getDailyToolCallLimit());
        return JSON.toJSONString(map);
    }

    /**
     * 预算窗口类型校验（工单 0158）：仅允许 DAY/WEEK/MONTH（大小写归一）或空（旧固定窗口兼容）。
     */
    private static String normalizeWindowType(String windowType) {
        if (windowType == null || windowType.isBlank()) {
            return null;
        }
        String normalized = windowType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!QuotaWindows.isSliding(normalized)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS,
                    "budgetWindowType 仅允许 DAY/WEEK/MONTH（空=旧固定窗口）: " + windowType);
        }
        return normalized;
    }

    /**
     * 模型白名单归一与结构校验（工单 0157）：条目 trim、空条目拒绝、上限 64 条防误配；
     * 空清单（null/全空）= 不限制（兼容存量），归一为 null。
     */
    private static java.util.List<String> normalizeAllowedModels(java.util.List<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return null;
        }
        java.util.List<String> normalized = new java.util.ArrayList<>();
        for (String entry : allowedModels) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS, "allowedModels 含空白条目（空=不限制，请传 null/空数组）");
            }
            normalized.add(trimmed);
        }
        if (normalized.size() > 64) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "allowedModels 条目数超上限（64）");
        }
        return normalized;
    }

    /** 跨实例广播密钥变更（协调服务未装配时仅本地失效） */
    private void notifyKeyChange() {
        ConfigHotReloadService coordinator = configHotReloadServiceProvider == null ? null
                : configHotReloadServiceProvider.getIfAvailable();
        if (coordinator != null) {
            coordinator.notifyChange(ConfigHotReloadService.TYPE_VIRTUAL_KEY, null);
        }
    }
}
