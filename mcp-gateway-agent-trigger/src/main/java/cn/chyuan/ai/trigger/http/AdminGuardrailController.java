package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminGuardrailService;
import cn.chyuan.ai.api.dto.GuardrailDTO;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 治理护栏管理接口（工单 0091：/admin/v1/guardrails）
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/guardrails")
public class AdminGuardrailController {

    @Resource
    private IAdminGuardrailService adminGuardrailService;

    @GetMapping
    public Response<List<GuardrailDTO>> list() {
        return Response.success(adminGuardrailService.listGuardrails());
    }

    @GetMapping("/{id}")
    public Response<GuardrailDTO> get(@PathVariable Long id) {
        return Response.success(adminGuardrailService.getGuardrail(id));
    }

    @PostMapping
    public Response<GuardrailDTO> create(@RequestBody GuardrailDTO dto) {
        return Response.success(adminGuardrailService.createGuardrail(dto));
    }

    @PutMapping("/{id}")
    public Response<GuardrailDTO> update(@PathVariable Long id, @RequestBody GuardrailDTO dto) {
        return Response.success(adminGuardrailService.updateGuardrail(id, dto));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        adminGuardrailService.deleteGuardrail(id);
        return Response.success(null);
    }
}
