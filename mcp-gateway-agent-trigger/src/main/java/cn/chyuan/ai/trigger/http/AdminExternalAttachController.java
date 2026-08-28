package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminExternalAttachService;
import cn.chyuan.ai.api.dto.ExternalAttachResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachTestResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachUpsertRequestDTO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * admin 外部 MCP 挂接管理接口（工单 0021：/admin/v1/external-attaches）
 *
 * <p>AdminJwtAuthFilter 统一做 JWT 认证 + 角色约束（ADMIN 读写 / READONLY 仅查）。
 * 挂接以 streamable HTTP / stdio 客户端连接上游，工具以
 * {@code attachName_toolName} 并入网关清单；连接失败状态可观测（list/test 呈现）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/external-attaches")
public class AdminExternalAttachController {

    @Resource
    private IAdminExternalAttachService adminExternalAttachService;

    /** 网关下挂接清单（含连接状态与工具数） */
    @GetMapping
    public Response<List<ExternalAttachResponseDTO>> list(@RequestParam String gatewayId) {
        return Response.success(adminExternalAttachService.listByGateway(gatewayId));
    }

    @GetMapping("/{id}")
    public Response<ExternalAttachResponseDTO> get(@PathVariable Long id) {
        return Response.success(adminExternalAttachService.getAttach(id));
    }

    @PostMapping
    public Response<ExternalAttachResponseDTO> create(@RequestBody ExternalAttachUpsertRequestDTO requestDTO) {
        return Response.success(adminExternalAttachService.createAttach(requestDTO));
    }

    @PutMapping("/{id}")
    public Response<ExternalAttachResponseDTO> update(@PathVariable Long id,
            @RequestBody ExternalAttachUpsertRequestDTO requestDTO) {
        return Response.success(adminExternalAttachService.updateAttach(id, requestDTO));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        adminExternalAttachService.deleteAttach(id);
        return Response.success(null);
    }

    /** 按请求体配置探活（保存前校验，不落库） */
    @PostMapping("/test")
    public Response<ExternalAttachTestResponseDTO> test(@RequestBody ExternalAttachUpsertRequestDTO requestDTO) {
        return Response.success(adminExternalAttachService.testAttach(requestDTO));
    }

    /** 按已存配置探活（保存后复检；成功时回写连接状态并刷新目录） */
    @PostMapping("/{id}/test")
    public Response<ExternalAttachTestResponseDTO> testById(@PathVariable Long id) {
        return Response.success(adminExternalAttachService.testAttachById(id));
    }
}
