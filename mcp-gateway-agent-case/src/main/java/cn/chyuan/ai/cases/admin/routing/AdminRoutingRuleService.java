package cn.chyuan.ai.cases.admin.routing;

import cn.chyuan.ai.api.IAdminRoutingRuleService;
import cn.chyuan.ai.api.dto.RoutingRuleResponseDTO;
import cn.chyuan.ai.api.dto.RoutingRuleUpsertRequestDTO;
import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;
import cn.chyuan.ai.domain.llmchannel.service.RoutingRuleAdminService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * admin tag 路由规则编排（工单 0160：DTO 转换）
 *
 * @author chyuan
 */
@Service
public class AdminRoutingRuleService implements IAdminRoutingRuleService {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private RoutingRuleAdminService routingRuleAdminService;

    @Override
    public RoutingRuleResponseDTO createRule(RoutingRuleUpsertRequestDTO requestDTO) {
        return toDto(routingRuleAdminService.create(toVo(null, requestDTO)));
    }

    @Override
    public RoutingRuleResponseDTO updateRule(Long id, RoutingRuleUpsertRequestDTO requestDTO) {
        return toDto(routingRuleAdminService.update(id, toVo(id, requestDTO)));
    }

    @Override
    public void deleteRule(Long id) {
        routingRuleAdminService.delete(id);
    }

    @Override
    public RoutingRuleResponseDTO getRule(Long id) {
        return toDto(routingRuleAdminService.get(id));
    }

    @Override
    public List<RoutingRuleResponseDTO> listRules() {
        return routingRuleAdminService.list().stream().map(this::toDto).toList();
    }

    private RoutingRuleVO toVo(Long id, RoutingRuleUpsertRequestDTO dto) {
        return RoutingRuleVO.builder()
                .id(id).ruleName(dto.getRuleName())
                .tagKey(dto.getTagKey()).tagValue(dto.getTagValue())
                .channelGroupId(dto.getChannelGroupId())
                .priority(dto.getPriority()).status(dto.getStatus())
                .build();
    }

    private RoutingRuleResponseDTO toDto(RoutingRuleVO vo) {
        return RoutingRuleResponseDTO.builder()
                .id(vo.getId()).ruleName(vo.getRuleName())
                .tagKey(vo.getTagKey()).tagValue(vo.getTagValue())
                .channelGroupId(vo.getChannelGroupId())
                .priority(vo.getPriority()).status(vo.getStatus())
                .createTime(format(vo.getCreateTime())).updateTime(format(vo.getUpdateTime()))
                .build();
    }

    private String format(Date value) {
        return value == null ? null : new SimpleDateFormat(DATE_PATTERN).format(value);
    }
}
