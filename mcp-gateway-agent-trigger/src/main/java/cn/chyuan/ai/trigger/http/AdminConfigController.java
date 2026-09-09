package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminConfigService;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 治理配置导出/导入接口（工单 0077）
 *
 * <p>导出快照可作灾备与环境迁移底稿；导入幂等（自然键 upsert）、
 * dry-run 只出差异预览；凭证密文跨环境要求 GOVERNANCE_ENC_KEY 一致。
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/config")
public class AdminConfigController {

    @Resource
    private IAdminConfigService adminConfigService;

    /** 导出全量治理配置快照 */
    @GetMapping("/export")
    public Response<Map<String, Object>> exportConfig() {
        return Response.success(adminConfigService.exportConfig());
    }

    /** 导入快照：?dryRun=true 仅差异预览；正式导入全程审计 */
    @PostMapping("/import")
    public Response<Map<String, Object>> importConfig(
            @RequestBody Map<String, Object> snapshot,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) {
        return Response.success(adminConfigService.importConfig(snapshot, dryRun));
    }
}
