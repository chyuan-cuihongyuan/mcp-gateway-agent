package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.infrastructure.dao.IMcpGatewayDao;
import cn.chyuan.ai.infrastructure.dao.IMcpGatewayToolDao;
import cn.chyuan.ai.infrastructure.dao.IMcpProtocolMappingDao;
import cn.chyuan.ai.infrastructure.dao.IMcpProtocolHttpDao;
import cn.chyuan.ai.infrastructure.dao.po.McpGatewayPO;
import cn.chyuan.ai.infrastructure.dao.po.McpGatewayToolPO;
import cn.chyuan.ai.infrastructure.dao.po.McpProtocolMappingPO;
import cn.chyuan.ai.infrastructure.dao.po.McpProtocolHttpPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话仓储服务
 *
 * @author chyuan
 *         2026/1/13 08:25
 */
@Slf4j
@Repository
public class SessionRepository implements ISessionRepository {

    @Resource
    private IMcpGatewayDao mcpGatewayDao;

    @Resource
    private IMcpGatewayToolDao mcpGatewayToolDao;

    @Resource
    private IMcpProtocolHttpDao mcpProtocolRegistryDao;

    @Resource
    private IMcpProtocolMappingDao mcpProtocolMappingDao;

    @Override
    public McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId) {
        McpGatewayPO mcpGatewayPO = mcpGatewayDao.queryMcpGatewayByGatewayId(gatewayId);
        if (null == mcpGatewayPO)
            return null;

        return McpGatewayConfigVO.builder()
                .gatewayId(mcpGatewayPO.getGatewayId())
                .gatewayName(mcpGatewayPO.getGatewayName())
                .gatewayDesc(mcpGatewayPO.getGatewayDesc())
                .version(mcpGatewayPO.getVersion())
                .build();
    }

    @Override
    public List<McpToolConfigVO> queryMcpGatewayToolConfigListByGatewayId(String gatewayId) {

        List<McpToolConfigVO> mcpToolConfigVOS = new ArrayList<>();

        // 1. 查询工具列表
        List<McpGatewayToolPO> mcpGatewayToolPOList = mcpGatewayToolDao.queryEffectiveTools(gatewayId);
        if (mcpGatewayToolPOList.isEmpty()) {
            return mcpToolConfigVOS;
        }

        // 2. 批量查询所有协议映射（避免 N+1 查询）
        List<Long> protocolIds = mcpGatewayToolPOList.stream()
                .map(McpGatewayToolPO::getProtocolId)
                .distinct()
                .collect(Collectors.toList());

        List<McpProtocolMappingPO> allMappings = mcpProtocolMappingDao.queryListByProtocolIds(protocolIds);

        // 按 protocolId 分组
        Map<Long, List<McpProtocolMappingPO>> mappingsByProtocolId = allMappings.stream()
                .collect(Collectors.groupingBy(McpProtocolMappingPO::getProtocolId));

        // 3. 组装参数信息
        for (McpGatewayToolPO tool : mcpGatewayToolPOList) {

            List<McpProtocolMappingPO> mappingPOList = mappingsByProtocolId.getOrDefault(tool.getProtocolId(),
                    new ArrayList<>());

            List<McpToolProtocolConfigVO.ProtocolMapping> requestProtocolMappings = new ArrayList<>();

            // 协议信息
            for (McpProtocolMappingPO mcpProtocolMappingPO : mappingPOList) {
                McpToolProtocolConfigVO.ProtocolMapping protocolMapping = McpToolProtocolConfigVO.ProtocolMapping
                        .builder()
                        .mappingType(mcpProtocolMappingPO.getMappingType())
                        .parentPath(mcpProtocolMappingPO.getParentPath())
                        .fieldName(mcpProtocolMappingPO.getFieldName())
                        .mcpPath(mcpProtocolMappingPO.getMcpPath())
                        .mcpType(mcpProtocolMappingPO.getMcpType())
                        .mcpDesc(mcpProtocolMappingPO.getMcpDesc())
                        .isRequired(mcpProtocolMappingPO.getIsRequired())
                        .sortOrder(mcpProtocolMappingPO.getSortOrder())
                        .build();
                requestProtocolMappings.add(protocolMapping);
            }

            // 组装数据
            McpToolConfigVO toolConfigVO = McpToolConfigVO.builder()
                    .gatewayId(tool.getGatewayId())
                    .toolId(tool.getToolId())
                    .toolName(tool.getToolName())
                    .toolDescription(tool.getToolDescription())
                    .toolVersion(tool.getToolVersion())
                    .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                            .requestProtocolMappings(requestProtocolMappings)
                            .build())
                    .build();

            mcpToolConfigVOS.add(toolConfigVO);
        }

        return mcpToolConfigVOS;
    }

    @Override
    public McpToolProtocolConfigVO queryMcpGatewayProtocolConfig(String gatewayId, String toolName) {

        // 获取协议ID - 根据网关ID + 工具名称
        McpGatewayToolPO mcpGatewayToolPOReq = new McpGatewayToolPO();
        mcpGatewayToolPOReq.setGatewayId(gatewayId);
        mcpGatewayToolPOReq.setToolName(toolName);
        Long protocolId = mcpGatewayToolDao.queryToolProtocolIdByToolName(mcpGatewayToolPOReq);

        // 查询协议
        McpProtocolHttpPO mcpProtocolHttpPO = mcpProtocolRegistryDao.queryMcpProtocolHttpByProtocolId(protocolId);
        if (null == mcpProtocolHttpPO)
            return null;

        McpToolProtocolConfigVO.HTTPConfig httpConfig = new McpToolProtocolConfigVO.HTTPConfig();
        httpConfig.setHttpUrl(mcpProtocolHttpPO.getHttpUrl());
        httpConfig.setHttpHeaders(mcpProtocolHttpPO.getHttpHeaders());
        httpConfig.setHttpMethod(mcpProtocolHttpPO.getHttpMethod());
        httpConfig.setTimeout(mcpProtocolHttpPO.getTimeout());

        return McpToolProtocolConfigVO.builder().httpConfig(httpConfig).build();
    }

}
