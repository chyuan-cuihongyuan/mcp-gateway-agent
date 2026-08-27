package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelRuleRepository;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.infrastructure.dao.ICelRuleDao;
import cn.chyuan.ai.infrastructure.dao.po.McpCelRulePO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * CEL 规则仓储实现（工单 0018）
 *
 * @author chyuan
 */
@Repository
public class CelRuleRepository implements ICelRuleRepository {

    @Resource
    private ICelRuleDao celRuleDao;

    @Override
    public void insert(CelRuleVO rule) {
        try {
            celRuleDao.insert(toPo(rule));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("规则名已存在: " + rule.getRuleName());
        }
    }

    @Override
    public boolean update(CelRuleVO rule) {
        return celRuleDao.update(toPo(rule)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return celRuleDao.deleteById(id) > 0;
    }

    @Override
    public List<CelRuleVO> findAllActive() {
        return celRuleDao.queryAllActive().stream().map(this::toVo).toList();
    }

    @Override
    public CelRuleVO findById(Long id) {
        return toVo(celRuleDao.queryById(id));
    }

    @Override
    public List<CelRuleVO> findByPage(String keyword, int offset, int size) {
        return celRuleDao.queryByPage(keyword, offset, size).stream().map(this::toVo).toList();
    }

    @Override
    public long count(String keyword) {
        Long count = celRuleDao.count(keyword);
        return count == null ? 0 : count;
    }

    private McpCelRulePO toPo(CelRuleVO vo) {
        if (vo == null) {
            return null;
        }
        return McpCelRulePO.builder()
                .id(vo.getId())
                .ruleName(vo.getRuleName())
                .expression(vo.getExpression())
                .scopeType(vo.getScopeType())
                .gatewayId(vo.getGatewayId())
                .virtualKeyId(vo.getVirtualKeyId())
                .status(vo.getStatus())
                .createdAt(vo.getCreatedAt())
                .updatedAt(vo.getUpdatedAt())
                .build();
    }

    private CelRuleVO toVo(McpCelRulePO po) {
        if (po == null) {
            return null;
        }
        return CelRuleVO.builder()
                .id(po.getId())
                .ruleName(po.getRuleName())
                .expression(po.getExpression())
                .scopeType(po.getScopeType())
                .gatewayId(po.getGatewayId())
                .virtualKeyId(po.getVirtualKeyId())
                .status(po.getStatus())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
