package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.infrastructure.dao.IVirtualKeyDao;
import cn.chyuan.ai.infrastructure.dao.IVirtualKeyGatewayDao;
import cn.chyuan.ai.infrastructure.dao.po.McpVirtualKeyPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 虚拟密钥仓储实现（工单 0017）
 *
 * @author chyuan
 */
@Slf4j
@Repository
public class VirtualKeyRepository implements IVirtualKeyRepository {

    @Resource
    private IVirtualKeyDao virtualKeyDao;

    @Resource
    private IVirtualKeyGatewayDao virtualKeyGatewayDao;

    @Resource
    private cn.chyuan.ai.infrastructure.dao.IMcpGatewayAuthDao legacyAuthDao;

    @Override
    public VirtualKeyVO findByHash(String apiKeyHash) {
        return toVo(virtualKeyDao.queryByHash(apiKeyHash));
    }

    @Override
    public VirtualKeyVO findById(Long id) {
        return toVo(virtualKeyDao.queryById(id));
    }

    @Override
    public VirtualKeyVO insert(String apiKeyHash, VirtualKeyVO vo) {
        McpVirtualKeyPO po = toPo(apiKeyHash, vo);
        virtualKeyDao.insert(po);
        vo.setId(po.getId());
        return vo;
    }

    @Override
    public int updateStatus(Long id, String status) {
        return virtualKeyDao.updateStatus(id, status);
    }

    @Override
    public int updateMeta(Long id, VirtualKeyVO vo) {
        McpVirtualKeyPO po = toPo(null, vo);
        po.setId(id);
        return virtualKeyDao.updateMeta(po);
    }

    @Override
    public void touchLastActive(Long id) {
        virtualKeyDao.touchLastActive(id);
    }

    @Override
    public boolean existsGrant(Long keyId, String gatewayId) {
        Integer count = virtualKeyGatewayDao.countGrant(keyId, gatewayId);
        return count != null && count > 0;
    }

    @Override
    public void insertGrant(Long keyId, String gatewayId) {
        virtualKeyGatewayDao.insertIgnore(keyId, gatewayId);
    }

    @Override
    public int deleteGrant(Long keyId, String gatewayId) {
        return virtualKeyGatewayDao.delete(keyId, gatewayId);
    }

    @Override
    public List<String> queryGrants(Long keyId) {
        List<String> gateways = virtualKeyGatewayDao.queryGatewaysByKeyId(keyId);
        return gateways == null ? Collections.emptyList() : gateways;
    }

    @Override
    public List<VirtualKeyVO> queryPage(String keyword, int offset, int size) {
        McpVirtualKeyPO query = new McpVirtualKeyPO();
        query.setKeyName(keyword);
        query.setLimitStart(offset);
        query.setLimitCount(size);
        List<McpVirtualKeyPO> list = virtualKeyDao.queryPage(query);
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    @Override
    public long count(String keyword) {
        McpVirtualKeyPO query = new McpVirtualKeyPO();
        query.setKeyName(keyword);
        Long count = virtualKeyDao.queryCount(query);
        return count == null ? 0 : count;
    }

    @Override
    public List<LegacyAuthRecord> queryLegacyAuthRecords() {
        List<cn.chyuan.ai.infrastructure.dao.po.McpGatewayAuthPO> legacy = legacyAuthDao.queryAll();
        if (legacy == null) {
            return Collections.emptyList();
        }
        return legacy.stream()
                .filter(po -> po.getApiKey() != null && !po.getApiKey().isBlank())
                .map(po -> new LegacyAuthRecord(po.getGatewayId(), po.getApiKey(), po.getRateLimit(),
                        po.getExpireTime(), po.getStatus()))
                .toList();
    }

    @Override
    public int migrateLegacy(String apiKeyHash, String keyName, String status, Integer rpmLimit, Date expiresAt, String gatewayId) {
        // 密钥已存在（上次迁移过）则跳过
        Long existingId = virtualKeyGatewayDao.queryKeyIdByHash(apiKeyHash);
        if (existingId != null) {
            // 授权幂等补齐
            virtualKeyGatewayDao.insertIgnore(existingId, gatewayId);
            return 0;
        }

        McpVirtualKeyPO po = McpVirtualKeyPO.builder()
                .apiKeyHash(apiKeyHash)
                .keyName(keyName)
                .status(status)
                .expiresAt(expiresAt)
                .rpmLimit(rpmLimit)
                .build();
        virtualKeyDao.insertIgnore(po);
        if (po.getId() != null) {
            virtualKeyGatewayDao.insertIgnore(po.getId(), gatewayId);
            return 1;
        }
        // 并发下唯一键冲突（getId 为空）→ 补授权
        Long conflictId = virtualKeyGatewayDao.queryKeyIdByHash(apiKeyHash);
        if (conflictId != null) {
            virtualKeyGatewayDao.insertIgnore(conflictId, gatewayId);
        }
        return 0;
    }

    private VirtualKeyVO toVo(McpVirtualKeyPO po) {
        if (po == null) {
            return null;
        }
        return VirtualKeyVO.builder()
                .id(po.getId())
                .keyName(po.getKeyName())
                .ownerUserId(po.getOwnerUserId())
                .tenantId(po.getTenantId())
                .status(po.getStatus())
                .expiresAt(po.getExpiresAt())
                .lastActiveAt(po.getLastActiveAt())
                .ipAllowList(parseIpList(po.getIpAllowList()))
                .rpmLimit(po.getRpmLimit())
                .dailyRequestLimit(po.getDailyRequestLimit())
                .dailyToolCallLimit(po.getDailyToolCallLimit())
                .tpmLimit(po.getTpmLimit())
                .dailyCostLimit(po.getDailyCostLimit())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private McpVirtualKeyPO toPo(String apiKeyHash, VirtualKeyVO vo) {
        return McpVirtualKeyPO.builder()
                .apiKeyHash(apiKeyHash)
                .keyName(vo.getKeyName())
                .ownerUserId(vo.getOwnerUserId())
                .tenantId(vo.getTenantId())
                .status(vo.getStatus())
                .expiresAt(vo.getExpiresAt())
                .ipAllowList(writeIpList(vo.getIpAllowList()))
                .rpmLimit(vo.getRpmLimit())
                .dailyRequestLimit(vo.getDailyRequestLimit())
                .dailyToolCallLimit(vo.getDailyToolCallLimit())
                .tpmLimit(vo.getTpmLimit())
                .dailyCostLimit(vo.getDailyCostLimit())
                .build();
    }

    /** ip_allow_list JSON 数组 ↔ List（空列表落库为 NULL，语义=不限） */
    private static List<String> parseIpList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> parsed = com.alibaba.fastjson.JSON.parseArray(json, String.class);
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (Exception e) {
            log.warn("ip_allow_list 解析失败（按不限制处理）：{}", json);
            return Collections.emptyList();
        }
    }

    private static String writeIpList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return com.alibaba.fastjson.JSON.toJSONString(list);
    }
}
