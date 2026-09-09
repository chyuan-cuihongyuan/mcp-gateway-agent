package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.ModelPricingRequestDTO;
import cn.chyuan.ai.api.dto.ModelPricingResponseDTO;

import java.util.List;

/**
 * 模型计价管理端口（工单 0085）
 *
 * @author chyuan
 */
public interface IAdminPricingService {

    List<ModelPricingResponseDTO> listPricing();

    ModelPricingResponseDTO getPricing(Long id);

    ModelPricingResponseDTO createPricing(ModelPricingRequestDTO requestDTO);

    ModelPricingResponseDTO updatePricing(Long id, ModelPricingRequestDTO requestDTO);

    void deletePricing(Long id);
}
