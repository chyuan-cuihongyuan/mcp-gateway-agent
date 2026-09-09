package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IConfigSnapshotPort;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleTemplateVO;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import cn.chyuan.ai.domain.governance.adapter.repository.ICelRuleRepository;
import cn.chyuan.ai.domain.governance.adapter.repository.ICelTemplateRepository;
import cn.chyuan.ai.domain.governance.adapter.repository.IWebhookEndpointRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.governance.service.ConfigSnapshotService;
import cn.chyuan.ai.infrastructure.dao.IMcpGatewayDao;
import cn.chyuan.ai.infrastructure.dao.IMcpGatewayToolDao;
import cn.chyuan.ai.infrastructure.dao.IMcpProtocolHttpDao;
import cn.chyuan.ai.infrastructure.dao.IMcpProtocolMappingDao;
import cn.chyuan.ai.infrastructure.dao.IVirtualKeyDao;
import cn.chyuan.ai.infrastructure.dao.po.McpGatewayPO;
import cn.chyuan.ai.infrastructure.dao.po.McpGatewayToolPO;
import cn.chyuan.ai.infrastructure.dao.po.McpProtocolHttpPO;
import cn.chyuan.ai.infrastructure.dao.po.McpProtocolMappingPO;
import cn.chyuan.ai.infrastructure.dao.po.McpVirtualKeyPO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Repository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置快照端口实现（工单 0077）
 *
 * <p>读侧直连既有 DAO/仓储组装快照行；密钥行只产元数据（哈希/明文不出库）。
 * 写侧按自然键 upsert：协议组以 (httpUrl, httpMethod) 定位（缺省生成 8 位
 * protocolId，与既有 ProtocolRepository 口径一致），工具的 protocolRef 在
 * 应用时解析为实际 protocolId（协议组先行导入，声明序保证）。
 *
 * @author chyuan
 */
@Repository
public class ConfigSnapshotPortImpl implements IConfigSnapshotPort {

    private static final int MAX_ROWS = 100_000;

    @Resource
    private IMcpGatewayDao gatewayDao;
    @Resource
    private IMcpGatewayToolDao toolDao;
    @Resource
    private IMcpProtocolHttpDao protocolHttpDao;
    @Resource
    private IMcpProtocolMappingDao protocolMappingDao;
    @Resource
    private IVirtualKeyDao virtualKeyDao;
    @Resource
    private ICelRuleRepository celRuleRepository;
    @Resource
    private ICelTemplateRepository celTemplateRepository;
    @Resource
    private IExternalAttachRepository externalAttachRepository;
    @Resource
    private ILlmChannelRepository llmChannelRepository;
    @Resource
    private IWebhookEndpointRepository webhookEndpointRepository;

    @Override
    public Map<String, Object> readCurrent() {
        Map<String, Object> stores = new LinkedHashMap<>();
        stores.put(ConfigSnapshotService.S_GATEWAYS, readGateways());
        stores.put(ConfigSnapshotService.S_PROTOCOLS, readProtocols());
        stores.put(ConfigSnapshotService.S_TOOLS, readTools());
        stores.put(ConfigSnapshotService.S_VK, readVirtualKeys());
        stores.put(ConfigSnapshotService.S_CEL_RULES, readCelRules());
        stores.put(ConfigSnapshotService.S_CEL_TEMPLATES, readCelTemplates());
        stores.put(ConfigSnapshotService.S_ATTACHES, readAttaches());
        stores.put(ConfigSnapshotService.S_LLM, readLlmChannels());
        stores.put(ConfigSnapshotService.S_WEBHOOKS, readWebhooks());
        return stores;
    }

    @Override
    public String upsert(String store, Map<String, Object> row) {
        return switch (store) {
            case ConfigSnapshotService.S_GATEWAYS -> upsertGateway(row);
            case ConfigSnapshotService.S_PROTOCOLS -> upsertProtocol(row);
            case ConfigSnapshotService.S_TOOLS -> upsertTool(row);
            case ConfigSnapshotService.S_CEL_TEMPLATES -> upsertCelTemplate(row);
            case ConfigSnapshotService.S_CEL_RULES -> upsertCelRule(row);
            case ConfigSnapshotService.S_ATTACHES -> upsertAttach(row);
            case ConfigSnapshotService.S_LLM -> upsertLlmChannel(row);
            case ConfigSnapshotService.S_WEBHOOKS -> upsertWebhook(row);
            default -> throw new AppException(McpErrorCodes.INVALID_PARAMS, "不支持的导入存储: " + store);
        };
    }

    // ---------- 读侧 ----------

    private List<Map<String, Object>> readGateways() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpGatewayPO po : gatewayDao.queryAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gatewayId", po.getGatewayId());
            row.put("gatewayName", po.getGatewayName());
            row.put("gatewayDesc", po.getGatewayDesc());
            row.put("version", po.getVersion());
            row.put("status", po.getStatus());
            row.put("auth", po.getAuth());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readProtocols() {
        List<McpProtocolHttpPO> https = protocolHttpDao.queryAll();
        List<McpProtocolMappingPO> mappings = protocolMappingDao.queryAll();
        Map<Long, List<McpProtocolMappingPO>> byProtocol = new LinkedHashMap<>();
        for (McpProtocolMappingPO mapping : mappings) {
            byProtocol.computeIfAbsent(mapping.getProtocolId(), k -> new ArrayList<>()).add(mapping);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpProtocolHttpPO po : https) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("httpUrl", po.getHttpUrl());
            row.put("httpMethod", po.getHttpMethod());
            row.put("httpHeaders", po.getHttpHeaders());
            row.put("timeout", po.getTimeout());
            row.put("retryTimes", po.getRetryTimes());
            row.put("status", po.getStatus());
            List<Map<String, Object>> mappingRows = new ArrayList<>();
            for (McpProtocolMappingPO m : byProtocol.getOrDefault(po.getProtocolId(), List.of())) {
                Map<String, Object> mr = new LinkedHashMap<>();
                mr.put("mappingType", m.getMappingType());
                mr.put("parentPath", m.getParentPath());
                mr.put("fieldName", m.getFieldName());
                mr.put("mcpPath", m.getMcpPath());
                mr.put("mcpType", m.getMcpType());
                mr.put("mcpDesc", m.getMcpDesc());
                mr.put("isRequired", m.getIsRequired());
                mr.put("sortOrder", m.getSortOrder());
                mappingRows.add(mr);
            }
            row.put("mappings", mappingRows);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readTools() {
        Map<Long, McpProtocolHttpPO> httpByProtocol = new LinkedHashMap<>();
        for (McpProtocolHttpPO po : protocolHttpDao.queryAll()) {
            httpByProtocol.put(po.getProtocolId(), po);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpGatewayToolPO po : toolDao.queryAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gatewayId", po.getGatewayId());
            row.put("toolId", po.getToolId());
            row.put("toolName", po.getToolName());
            row.put("toolType", po.getToolType());
            row.put("toolDescription", po.getToolDescription());
            row.put("toolVersion", po.getToolVersion());
            row.put("protocolType", po.getProtocolType());
            McpProtocolHttpPO http = httpByProtocol.get(po.getProtocolId());
            if (http != null) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("httpUrl", http.getHttpUrl());
                ref.put("httpMethod", http.getHttpMethod());
                row.put("protocolRef", ref);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readVirtualKeys() {
        McpVirtualKeyPO page = new McpVirtualKeyPO();
        page.setLimitStart(0);
        page.setLimitCount(MAX_ROWS);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpVirtualKeyPO po : virtualKeyDao.queryPage(page)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("keyName", po.getKeyName());
            row.put("ownerUserId", po.getOwnerUserId());
            row.put("tenantId", po.getTenantId());
            row.put("status", po.getStatus());
            row.put("expiresAt", po.getExpiresAt() == null ? null : format.format(po.getExpiresAt()));
            row.put("ipAllowList", parseIpList(po.getIpAllowList()));
            row.put("budgetSoft", po.getBudgetSoft());
            row.put("budgetHard", po.getBudgetHard());
            row.put("budgetDurationHours", po.getBudgetDurationHours());
            row.put("rpmLimit", po.getRpmLimit());
            row.put("dailyRequestLimit", po.getDailyRequestLimit());
            row.put("dailyToolCallLimit", po.getDailyToolCallLimit());
            row.put("tpmLimit", po.getTpmLimit());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readCelRules() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CelRuleVO vo : celRuleRepository.findByPage("", 0, MAX_ROWS)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleName", vo.getRuleName());
            row.put("expression", vo.getExpression());
            row.put("scopeType", vo.getScopeType());
            row.put("gatewayId", vo.getGatewayId());
            row.put("virtualKeyId", vo.getVirtualKeyId());
            row.put("status", vo.getStatus());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readCelTemplates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CelRuleTemplateVO vo : celTemplateRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", vo.getCode());
            row.put("name", vo.getName());
            row.put("expression", vo.getExpression());
            row.put("variablesDesc", vo.getVariablesDesc());
            row.put("builtin", vo.getBuiltin());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readAttaches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExternalAttachVO vo : externalAttachRepository.findAllAttaches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gatewayId", vo.getGatewayId());
            row.put("attachName", vo.getAttachName());
            row.put("transportType", vo.getTransportType());
            row.put("endpoint", vo.getEndpoint());
            row.put("apiKey", vo.getApiKey());
            row.put("command", vo.getCommand());
            row.put("args", vo.getArgs());
            row.put("env", vo.getEnv());
            row.put("requestTimeoutMs", vo.getRequestTimeoutMs());
            row.put("status", vo.getStatus());
            row.put("weight", vo.getWeight());
            row.put("priority", vo.getPriority());
            row.put("authType", vo.getAuthType());
            row.put("authConfig", vo.getAuthConfig());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readLlmChannels() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LlmChannelVO vo : llmChannelRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", vo.getName());
            row.put("baseUrl", vo.getBaseUrl());
            row.put("credential", vo.getCredential());
            row.put("models", vo.getModels());
            row.put("modelMapping", vo.getModelMapping());
            row.put("weight", vo.getWeight());
            row.put("priority", vo.getPriority());
            row.put("status", vo.getStatus());
            row.put("timeoutMs", vo.getTimeoutMs());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> readWebhooks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WebhookEndpointVO vo : webhookEndpointRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", vo.getName());
            row.put("url", vo.getUrl());
            row.put("events", vo.getEvents());
            row.put("secret", vo.getSecret());
            row.put("enabled", vo.getEnabled());
            rows.add(row);
        }
        return rows;
    }

    // ---------- 写侧（自然键 upsert） ----------

    private String upsertGateway(Map<String, Object> row) {
        String gatewayId = str(row, "gatewayId");
        McpGatewayPO existing = gatewayDao.queryMcpGatewayByGatewayId(gatewayId);
        McpGatewayPO po = McpGatewayPO.builder()
                .id(existing == null ? null : existing.getId())
                .gatewayId(gatewayId)
                .gatewayName(str(row, "gatewayName"))
                .gatewayDesc(str(row, "gatewayDesc"))
                .version(str(row, "version"))
                .status(intVal(row, "status"))
                .auth(intVal(row, "auth"))
                .build();
        if (existing == null) {
            gatewayDao.insert(po);
            return "created";
        }
        gatewayDao.updateById(po);
        return "updated";
    }

    @SuppressWarnings("unchecked")
    private String upsertProtocol(Map<String, Object> row) {
        String url = str(row, "httpUrl");
        String method = str(row, "httpMethod");
        McpProtocolHttpPO existing = findProtocol(url, method);
        Long protocolId = existing == null ? Long.parseLong(RandomStringUtils.randomNumeric(8))
                : existing.getProtocolId();
        McpProtocolHttpPO po = McpProtocolHttpPO.builder()
                .protocolId(protocolId)
                .httpUrl(url)
                .httpMethod(method)
                .httpHeaders(str(row, "httpHeaders"))
                .timeout(intVal(row, "timeout"))
                .retryTimes(intVal(row, "retryTimes") == null ? 3 : intVal(row, "retryTimes"))
                .status(intVal(row, "status"))
                .build();
        if (existing == null) {
            protocolHttpDao.insert(po);
        } else {
            protocolHttpDao.updateByProtocolId(po);
        }
        // 映射全量重建（与既有 ProtocolRepository 更新口径一致）
        protocolMappingDao.deleteByProtocolId(protocolId);
        Object mappings = row.get("mappings");
        if (mappings instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> mr = (Map<String, Object>) item;
                protocolMappingDao.insert(McpProtocolMappingPO.builder()
                        .protocolId(protocolId)
                        .mappingType(str(mr, "mappingType"))
                        .parentPath(str(mr, "parentPath"))
                        .fieldName(str(mr, "fieldName"))
                        .mcpPath(str(mr, "mcpPath"))
                        .mcpType(str(mr, "mcpType"))
                        .mcpDesc(str(mr, "mcpDesc"))
                        .isRequired(intVal(mr, "isRequired"))
                        .sortOrder(intVal(mr, "sortOrder"))
                        .build());
            }
        }
        return existing == null ? "created" : "updated";
    }

    @SuppressWarnings("unchecked")
    private String upsertTool(Map<String, Object> row) {
        String gatewayId = str(row, "gatewayId");
        String toolName = str(row, "toolName");
        Long protocolId = resolveProtocolId((Map<String, Object>) row.get("protocolRef"));
        if (protocolId == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS,
                    "工具 " + gatewayId + "/" + toolName + " 的协议组缺失——请先导入对应 protocolHttp");
        }
        McpGatewayToolPO existing = findTool(gatewayId, toolName);
        McpGatewayToolPO po = new McpGatewayToolPO();
        po.setGatewayId(gatewayId);
        po.setToolId(longVal(row, "toolId"));
        po.setToolName(toolName);
        po.setToolType(str(row, "toolType"));
        po.setToolDescription(str(row, "toolDescription"));
        po.setToolVersion(str(row, "toolVersion"));
        po.setProtocolId(protocolId);
        po.setProtocolType(str(row, "protocolType"));
        if (existing != null) {
            toolDao.deleteByToolId(existing.getToolId());
        }
        toolDao.insert(po);
        return existing == null ? "created" : "updated";
    }

    private String upsertCelTemplate(Map<String, Object> row) {
        String code = str(row, "code");
        CelRuleTemplateVO existing = celTemplateRepository.findByCode(code);
        if (existing != null && Integer.valueOf(1).equals(existing.getBuiltin())) {
            // 内置模板由种子幂等保证，不覆盖
            return "updated";
        }
        CelRuleTemplateVO vo = CelRuleTemplateVO.builder()
                .id(existing == null ? null : existing.getId())
                .code(code)
                .name(str(row, "name"))
                .expression(str(row, "expression"))
                .variablesDesc(str(row, "variablesDesc"))
                .builtin(existing == null ? 0 : existing.getBuiltin())
                .build();
        if (existing == null) {
            celTemplateRepository.insert(vo);
            return "created";
        }
        celTemplateRepository.update(vo);
        return "updated";
    }

    private String upsertCelRule(Map<String, Object> row) {
        String ruleName = str(row, "ruleName");
        String scopeType = str(row, "scopeType");
        String gatewayId = str(row, "gatewayId");
        Long virtualKeyId = longVal(row, "virtualKeyId");
        CelRuleVO existing = celRuleRepository.findByPage("", 0, MAX_ROWS).stream()
                .filter(vo -> java.util.Objects.equals(vo.getRuleName(), ruleName)
                        && java.util.Objects.equals(vo.getScopeType(), scopeType)
                        && java.util.Objects.equals(vo.getGatewayId(), gatewayId)
                        && java.util.Objects.equals(vo.getVirtualKeyId(), virtualKeyId))
                .findFirst().orElse(null);
        CelRuleVO vo = CelRuleVO.builder()
                .id(existing == null ? null : existing.getId())
                .ruleName(ruleName)
                .expression(str(row, "expression"))
                .scopeType(scopeType)
                .gatewayId(gatewayId)
                .virtualKeyId(virtualKeyId)
                .status(str(row, "status"))
                .build();
        if (existing == null) {
            celRuleRepository.insert(vo);
            return "created";
        }
        celRuleRepository.update(vo);
        return "updated";
    }

    private String upsertAttach(Map<String, Object> row) {
        String gatewayId = str(row, "gatewayId");
        String attachName = str(row, "attachName");
        ExternalAttachVO existing = externalAttachRepository.findByGatewayId(gatewayId).stream()
                .filter(vo -> attachName.equals(vo.getAttachName()))
                .findFirst().orElse(null);
        ExternalAttachVO vo = ExternalAttachVO.builder()
                .id(existing == null ? null : existing.getId())
                .gatewayId(gatewayId)
                .attachName(attachName)
                .transportType(str(row, "transportType"))
                .endpoint(str(row, "endpoint"))
                .apiKey(str(row, "apiKey"))
                .command(str(row, "command"))
                .args(str(row, "args"))
                .env(str(row, "env"))
                .requestTimeoutMs(intVal(row, "requestTimeoutMs"))
                .status(intVal(row, "status"))
                .weight(intVal(row, "weight"))
                .priority(intVal(row, "priority"))
                .authType(str(row, "authType"))
                .authConfig(str(row, "authConfig"))
                .build();
        if (existing == null) {
            externalAttachRepository.insert(vo);
            return "created";
        }
        externalAttachRepository.update(vo);
        return "updated";
    }

    private String upsertLlmChannel(Map<String, Object> row) {
        String name = str(row, "name");
        LlmChannelVO existing = llmChannelRepository.findByName(name);
        LlmChannelVO vo = LlmChannelVO.builder()
                .id(existing == null ? null : existing.getId())
                .name(name)
                .baseUrl(str(row, "baseUrl"))
                .credential(str(row, "credential"))
                .models(str(row, "models"))
                .modelMapping(str(row, "modelMapping"))
                .weight(intVal(row, "weight"))
                .priority(intVal(row, "priority"))
                .status(intVal(row, "status"))
                .timeoutMs(intVal(row, "timeoutMs"))
                .build();
        if (existing == null) {
            llmChannelRepository.insert(vo);
            return "created";
        }
        llmChannelRepository.update(vo);
        return "updated";
    }

    private String upsertWebhook(Map<String, Object> row) {
        String url = str(row, "url");
        WebhookEndpointVO existing = webhookEndpointRepository.findAll().stream()
                .filter(vo -> url.equals(vo.getUrl()))
                .findFirst().orElse(null);
        WebhookEndpointVO vo = WebhookEndpointVO.builder()
                .id(existing == null ? null : existing.getId())
                .name(str(row, "name"))
                .url(url)
                .events(listVal(row, "events"))
                .secret(str(row, "secret"))
                .enabled(intVal(row, "enabled"))
                .build();
        if (existing == null) {
            webhookEndpointRepository.insert(vo);
            return "created";
        }
        webhookEndpointRepository.update(vo);
        return "updated";
    }

    // ---------- 辅助 ----------

    private McpProtocolHttpPO findProtocol(String url, String method) {
        for (McpProtocolHttpPO po : protocolHttpDao.queryAll()) {
            if (url != null && url.equals(po.getHttpUrl()) && method != null && method.equals(po.getHttpMethod())) {
                return po;
            }
        }
        return null;
    }

    private Long resolveProtocolId(Map<String, Object> protocolRef) {
        if (protocolRef == null) {
            return null;
        }
        McpProtocolHttpPO po = findProtocol(str(protocolRef, "httpUrl"), str(protocolRef, "httpMethod"));
        return po == null ? null : po.getProtocolId();
    }

    private McpGatewayToolPO findTool(String gatewayId, String toolName) {
        for (McpGatewayToolPO po : toolDao.queryListByGatewayId(gatewayId)) {
            if (toolName != null && toolName.equals(po.getToolName())) {
                return po;
            }
        }
        return null;
    }

    private static String str(Map<String, Object> row, String field) {
        Object value = row == null ? null : row.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intVal(Map<String, Object> row, String field) {
        Object value = row == null ? null : row.get(field);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longVal(Map<String, Object> row, String field) {
        Object value = row == null ? null : row.get(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listVal(Map<String, Object> row, String field) {
        Object value = row == null ? null : row.get(field);
        return value instanceof List ? (List<String>) value : null;
    }

    /** ip_allow_list JSON 字符串 → 列表（与 VirtualKeyRepository 同口径） */
    private static List<String> parseIpList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = com.alibaba.fastjson.JSON.parseArray(json, String.class);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }
}
