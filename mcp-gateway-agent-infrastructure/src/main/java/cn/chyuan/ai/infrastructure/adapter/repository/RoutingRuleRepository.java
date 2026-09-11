package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.adapter.repository.IRoutingRuleRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;
import cn.chyuan.ai.infrastructure.dao.IRoutingRuleDao;
import cn.chyuan.ai.infrastructure.dao.po.McpRoutingRulePO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * tag 路由规则仓储实现（工单 0160）
 *
 * @author chyuan
 */
@Repository
public class RoutingRuleRepository implements IRoutingRuleRepository {

    @Resource
    private IRoutingRuleDao dao;

    @Override
    public Long insert(RoutingRuleVO rule) {
        McpRoutingRulePO po = toPo(rule);
        dao.insert(po);
        return po.getId();
    }

    @Override
    public boolean update(RoutingRuleVO rule) {
        return dao.update(toPo(rule)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return dao.deleteById(id) > 0;
    }

    @Override
    public RoutingRuleVO findById(Long id) {
        return toVo(dao.queryById(id));
    }

    @Override
    public RoutingRuleVO findByName(String ruleName) {
        return toVo(dao.queryByName(ruleName));
    }

    @Override
    public List<RoutingRuleVO> findAll() {
        List<McpRoutingRulePO> list = dao.queryAll();
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    @Override
    public List<RoutingRuleVO> findActive() {
        List<McpRoutingRulePO> list = dao.queryActive();
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    private McpRoutingRulePO toPo(RoutingRuleVO vo) {
        return McpRoutingRulePO.builder()
                .id(vo.getId()).ruleName(vo.getRuleName())
                .tagKey(vo.getTagKey()).tagValue(vo.getTagValue())
                .channelGroupId(vo.getChannelGroupId())
                .priority(vo.getPriority()).status(vo.getStatus())
                .build();
    }

    private RoutingRuleVO toVo(McpRoutingRulePO po) {
        if (po == null) {
            return null;
        }
        return RoutingRuleVO.builder()
                .id(po.getId()).ruleName(po.getRuleName())
                .tagKey(po.getTagKey()).tagValue(po.getTagValue())
                .channelGroupId(po.getChannelGroupId())
                .priority(po.getPriority()).status(po.getStatus())
                .createTime(po.getCreateTime()).updateTime(po.getUpdateTime())
                .build();
    }
}
