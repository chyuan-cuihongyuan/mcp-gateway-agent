package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminPricingService;
import cn.chyuan.ai.api.dto.ModelPricingRequestDTO;
import cn.chyuan.ai.api.dto.ModelPricingResponseDTO;
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
 * 模型计价管理接口（工单 0085：/admin/v1/pricing）
 *
 * <p>角色语义由 AdminJwtAuthFilter 矩阵承载（读=READONLY 起、写=ADMIN 起）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/pricing")
public class AdminPricingController {

    @Resource
    private IAdminPricingService adminPricingService;

    @GetMapping
    public Response<List<ModelPricingResponseDTO>> list() {
        return Response.success(adminPricingService.listPricing());
    }

    @GetMapping("/{id}")
    public Response<ModelPricingResponseDTO> get(@PathVariable Long id) {
        return Response.success(adminPricingService.getPricing(id));
    }

    @PostMapping
    public Response<ModelPricingResponseDTO> create(@RequestBody ModelPricingRequestDTO requestDTO) {
        return Response.success(adminPricingService.createPricing(requestDTO));
    }

    @PutMapping("/{id}")
    public Response<ModelPricingResponseDTO> update(@PathVariable Long id,
            @RequestBody ModelPricingRequestDTO requestDTO) {
        return Response.success(adminPricingService.updatePricing(id, requestDTO));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        adminPricingService.deletePricing(id);
        return Response.success(null);
    }
}
