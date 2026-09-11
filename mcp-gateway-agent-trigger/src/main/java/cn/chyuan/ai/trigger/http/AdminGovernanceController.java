package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminGovernanceService;
import cn.chyuan.ai.api.dto.AuditLogResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleUpsertRequestDTO;
import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.LoginResponseDTO;
import cn.chyuan.ai.api.dto.UsageDailyResponseDTO;
import cn.chyuan.ai.api.dto.UsageLogResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyCreateRequestDTO;
import cn.chyuan.ai.api.dto.VirtualKeyResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyUpdateRequestDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.api.response.ResponsePage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * admin 治理面控制台接口（工单 0017：/admin/v1/*）
 *
 * <p>登录免 JWT（AdminJwtAuthFilter 白名单）；其余接口由过滤器做
 * JWT 认证 + 角色约束（ADMIN 读写 / READONLY 仅查）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1")
public class AdminGovernanceController {

    @Resource
    private cn.chyuan.ai.domain.governance.service.IAuditService auditService;

    @Resource
    private IAdminGovernanceService adminGovernanceService;

    /** 登录：签发 JWT（免认证路径） */
    @PostMapping("/auth/login")
    public Response<LoginResponseDTO> login(@RequestBody LoginRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.login(requestDTO));
    }

    /** 创建虚拟密钥 —— 明文凭证仅本次响应返回一次 */
    @PostMapping("/virtual-keys")
    public Response<VirtualKeyResponseDTO> createVirtualKey(@RequestBody VirtualKeyCreateRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.createVirtualKey(requestDTO));
    }

    @PutMapping("/virtual-keys/{id}")
    public Response<VirtualKeyResponseDTO> updateVirtualKey(@PathVariable Long id,
            @RequestBody VirtualKeyUpdateRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.updateVirtualKey(id, requestDTO));
    }

    @DeleteMapping("/virtual-keys/{id}")
    public Response<Void> revokeVirtualKey(@PathVariable Long id) {
        adminGovernanceService.revokeVirtualKey(id);
        return Response.success(null);
    }

    @PostMapping("/virtual-keys/{id}/grants")
    public Response<Void> grantGateway(@PathVariable Long id, @RequestParam String gatewayId) {
        adminGovernanceService.grantGateway(id, gatewayId);
        return Response.success(null);
    }

    @DeleteMapping("/virtual-keys/{id}/grants")
    public Response<Void> revokeGrantGateway(@PathVariable Long id, @RequestParam String gatewayId) {
        adminGovernanceService.revokeGrantGateway(id, gatewayId);
        return Response.success(null);
    }

    @GetMapping("/virtual-keys/{id}")
    public Response<VirtualKeyResponseDTO> getVirtualKey(@PathVariable Long id) {
        return Response.success(adminGovernanceService.getVirtualKey(id));
    }

    /** 轮换密钥（工单 0049）—— 新明文凭证仅本次响应返回一次；旧钥进入宽限期并存 */
    @PostMapping("/virtual-keys/{id}/regenerate")
    public Response<VirtualKeyResponseDTO> regenerateVirtualKey(@PathVariable Long id) {
        return Response.success(adminGovernanceService.regenerateVirtualKey(id));
    }

    // ---- 密钥管理完备化（工单 0052）----

    @PostMapping("/virtual-keys/{id}/block")
    public Response<Void> blockVirtualKey(@PathVariable Long id) {
        adminGovernanceService.blockVirtualKey(id);
        return Response.success(null);
    }

    @PostMapping("/virtual-keys/{id}/unblock")
    public Response<Void> unblockVirtualKey(@PathVariable Long id) {
        adminGovernanceService.unblockVirtualKey(id);
        return Response.success(null);
    }

    /** 批量禁用/解禁，body {"ids":[1,2],"action":"block"|"unblock"} */
    @PostMapping("/virtual-keys/bulk")
    public Response<Integer> bulkVirtualKeys(@RequestBody java.util.Map<String, Object> body) {
        java.util.List<Long> ids = ((java.util.List<?>) body.getOrDefault("ids", java.util.List.of())).stream()
                .map(v -> Long.valueOf(String.valueOf(v)))
                .toList();
        boolean block = "block".equalsIgnoreCase(String.valueOf(body.get("action")));
        return Response.success(adminGovernanceService.bulkBlockVirtualKeys(ids, block));
    }

    /** 临时提额，body {"increase":1000,"expiresAt":"yyyy-MM-dd HH:mm:ss"} */
    @PostMapping("/virtual-keys/{id}/temp-budget")
    public Response<Void> applyTempBudget(@PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        long increase = Long.parseLong(String.valueOf(body.get("increase")));
        String expiresAt = String.valueOf(body.get("expiresAt"));
        adminGovernanceService.applyTempBudget(id, increase, expiresAt);
        return Response.success(null);
    }

    @GetMapping("/virtual-keys")
    public ResponsePage<List<VirtualKeyResponseDTO>> pageVirtualKeys(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return adminGovernanceService.pageVirtualKeys(keyword, page, size);
    }

    @GetMapping("/audit-logs")
    public ResponsePage<List<AuditLogResponseDTO>> pageAuditLogs(
            @RequestParam(required = false, defaultValue = "") String resourceType,
            @RequestParam(required = false, defaultValue = "") String resourceId,
            @RequestParam(required = false, defaultValue = "") String type,
            @RequestParam(required = false, defaultValue = "") String actor,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return adminGovernanceService.pageAuditLogs(resourceType, resourceId, type, actor, page, size);
    }

    /** 审计统计（工单 0186 Z3）：分型分布 + 操作者 TopN */
    @org.springframework.web.bind.annotation.GetMapping("/audit/stats")
    public Response<java.util.Map<String, Object>> auditStats(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        return Response.success(auditService.stats(days));
    }

    /** 审计导出（工单 0112）：format=csv|json，时间/分型/操作者筛选 */
    @GetMapping("/audit-logs/export")
    public Response<String> exportAuditLogs(
            @RequestParam(required = false, defaultValue = "csv") String format,
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate,
            @RequestParam(required = false, defaultValue = "") String type,
            @RequestParam(required = false, defaultValue = "") String actor) {
        return Response.success(adminGovernanceService.exportAuditLogs(format, fromDate, toDate, type, actor));
    }

    // ---- 用量账本（工单 0046）----

    /** 用量明细分页（时间/密钥/工具/状态/流量类型/标签过滤；读操作不记审计） */
    @GetMapping("/usage/logs")
    public ResponsePage<List<UsageLogResponseDTO>> pageUsageLogs(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate,
            @RequestParam(required = false) Long virtualKeyId,
            @RequestParam(required = false, defaultValue = "") String toolOrModel,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "") String trafficType,
            @RequestParam(required = false, defaultValue = "") String channelId,
            @RequestParam(required = false, defaultValue = "") String tag,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return adminGovernanceService.pageUsageLogs(fromDate, toDate, virtualKeyId,
                toolOrModel, status, trafficType, channelId, tag, page, size);
    }

    /** 用量日聚合明细（区间内全部维度行，对账/明细页用） */
    @GetMapping("/usage/daily")
    public Response<List<UsageDailyResponseDTO>> dailyUsageDetail(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        return Response.success(adminGovernanceService.dailyUsageDetail(fromDate, toDate));
    }

    // ---- CEL 规则模板（工单 0057）----

    @GetMapping("/cel-templates")
    public Response<List<cn.chyuan.ai.api.dto.CelTemplateResponseDTO>> listCelTemplates() {
        return Response.success(adminGovernanceService.listCelTemplates());
    }

    @PostMapping("/cel-templates")
    public Response<cn.chyuan.ai.api.dto.CelTemplateResponseDTO> createCelTemplate(
            @RequestBody cn.chyuan.ai.api.dto.CelTemplateUpsertRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.createCelTemplate(requestDTO));
    }

    @DeleteMapping("/cel-templates/{id}")
    public Response<Void> deleteCelTemplate(@PathVariable Long id) {
        adminGovernanceService.deleteCelTemplate(id);
        return Response.success(null);
    }

    /** 实例化模板为规则（渲染占位符 → 既有保存校验链） */
    @PostMapping("/cel-templates/instantiate")
    public Response<CelRuleResponseDTO> instantiateCelTemplate(
            @RequestBody cn.chyuan.ai.api.dto.CelTemplateInstantiateRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.instantiateCelTemplate(requestDTO));
    }

    /** 用量按日汇总（趋势图数据源） */
    @GetMapping("/usage/daily/totals")
    public Response<List<UsageDailyResponseDTO>> dailyUsageTotals(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        return Response.success(adminGovernanceService.dailyUsageTotals(fromDate, toDate));
    }

    /** 按标签日聚合（工单 0088：标签维度调用/失败/成本趋势） */
    @GetMapping("/usage/tags/daily")
    public Response<List<cn.chyuan.ai.api.dto.UsageTagDailyDTO>> dailyUsageByTag(
            @RequestParam String tag,
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        return Response.success(adminGovernanceService.dailyUsageByTag(tag, fromDate, toDate));
    }

    /** 成本日趋势（工单 0089） */
    @GetMapping("/usage/cost/daily")
    public Response<List<cn.chyuan.ai.api.dto.CostDailyDTO>> costDaily(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        return Response.success(adminGovernanceService.costDaily(fromDate, toDate));
    }

    /** 成本 TopN（工单 0089：dimension=model|tag） */
    @GetMapping("/usage/cost/topn")
    public Response<List<cn.chyuan.ai.api.dto.CostTopNDTO>> costTopN(
            @RequestParam(required = false, defaultValue = "model") String dimension,
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate,
            @RequestParam(required = false, defaultValue = "5") int top) {
        return Response.success(adminGovernanceService.costTopN(dimension, fromDate, toDate, top));
    }

    /** 未定价占比（工单 0089） */
    @GetMapping("/usage/cost/unpriced")
    public Response<java.util.Map<String, Object>> unpricedStats(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        return Response.success(adminGovernanceService.unpricedStats(fromDate, toDate));
    }

    /** 账单导出 CSV（工单 0090：month=yyyy-MM 或自定义起止；text/csv 直出，AUDITOR 起） */
    @GetMapping("/usage/billing/export")
    public void billingExport(
            jakarta.servlet.http.HttpServletResponse response,
            @RequestParam(required = false, defaultValue = "") String month,
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate,
            @RequestParam(required = false) Long virtualKeyId,
            @RequestParam(required = false, defaultValue = "") String tag) throws java.io.IOException {
        if (!fromDate.isBlank() || !toDate.isBlank()) {
            // 自定义区间
        } else if (!month.isBlank() && month.matches("\\d{4}-\\d{2}")) {
            fromDate = month + "-01";
            toDate = month + "-31";
        } else {
            java.time.LocalDate now = java.time.LocalDate.now().withDayOfMonth(1);
            fromDate = now.toString();
            toDate = now.plusMonths(1).minusDays(1).toString();
        }
        String csv = adminGovernanceService.billingExportCsv(fromDate, toDate, virtualKeyId, tag);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=billing-" + fromDate + "_" + toDate + ".csv");
        response.getWriter().write("\uFEFF" + csv);
        response.getWriter().flush();
    }

    // ---- CEL 工具治理规则（工单 0018）----

    /** 仅校验表达式（不落库）：data 为 null 表示合法，否则为错误原因 */
    @GetMapping("/cel-rules/validate")
    public Response<String> validateCelExpression(@RequestParam String expression) {
        return Response.success(adminGovernanceService.validateCelExpression(expression));
    }

    /** 假想调用上下文试跑（工单 0076 playground）：不落库不计数 */
    @PostMapping("/cel-rules/dry-run")
    public Response<cn.chyuan.ai.api.dto.CelDryRunResponseDTO> celDryRun(
            @RequestBody cn.chyuan.ai.api.dto.CelDryRunRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.celDryRun(requestDTO));
    }

    /** 创建规则：保存时编译校验，非法表达式返回错误原因 */
    @PostMapping("/cel-rules")
    public Response<CelRuleResponseDTO> createCelRule(@RequestBody CelRuleUpsertRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.createCelRule(requestDTO));
    }

    @PutMapping("/cel-rules/{id}")
    public Response<CelRuleResponseDTO> updateCelRule(@PathVariable Long id,
            @RequestBody CelRuleUpsertRequestDTO requestDTO) {
        return Response.success(adminGovernanceService.updateCelRule(id, requestDTO));
    }

    @DeleteMapping("/cel-rules/{id}")
    public Response<Void> deleteCelRule(@PathVariable Long id) {
        adminGovernanceService.deleteCelRule(id);
        return Response.success(null);
    }

    @GetMapping("/cel-rules/{id}")
    public Response<CelRuleResponseDTO> getCelRule(@PathVariable Long id) {
        return Response.success(adminGovernanceService.getCelRule(id));
    }

    @GetMapping("/cel-rules")
    public ResponsePage<List<CelRuleResponseDTO>> pageCelRules(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return adminGovernanceService.pageCelRules(keyword, page, size);
    }
}
