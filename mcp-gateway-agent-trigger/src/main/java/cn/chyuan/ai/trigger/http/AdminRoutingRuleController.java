package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminRoutingRuleService;
import cn.chyuan.ai.api.dto.RoutingRuleResponseDTO;
import cn.chyuan.ai.api.dto.RoutingRuleUpsertRequestDTO;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * admin tag 路由规则控制台接口（工单 0160：/admin/v1/routing-rules）
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/routing-rules")
public class AdminRoutingRuleController {

    @Resource
    private IAdminRoutingRuleService adminRoutingRuleService;

    @PostMapping
    public Response<RoutingRuleResponseDTO> createRule(@RequestBody RoutingRuleUpsertRequestDTO requestDTO) {
        return Response.success(adminRoutingRuleService.createRule(requestDTO));
    }

    @GetMapping
    public Response<List<RoutingRuleResponseDTO>> listRules() {
        return Response.success(adminRoutingRuleService.listRules());
    }

    @GetMapping("/{id}")
    public Response<RoutingRuleResponseDTO> getRule(@PathVariable Long id) {
        return Response.success(adminRoutingRuleService.getRule(id));
    }

    @PutMapping("/{id}")
    public Response<RoutingRuleResponseDTO> updateRule(@PathVariable Long id,
            @RequestBody RoutingRuleUpsertRequestDTO requestDTO) {
        return Response.success(adminRoutingRuleService.updateRule(id, requestDTO));
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteRule(@PathVariable Long id) {
        adminRoutingRuleService.deleteRule(id);
        return Response.success(null);
    }
}
