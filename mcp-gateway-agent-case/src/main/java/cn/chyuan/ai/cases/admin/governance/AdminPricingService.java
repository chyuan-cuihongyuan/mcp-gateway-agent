package cn.chyuan.ai.cases.admin.governance;

import cn.chyuan.ai.api.IAdminPricingService;
import cn.chyuan.ai.api.dto.ModelPricingRequestDTO;
import cn.chyuan.ai.api.dto.ModelPricingResponseDTO;
import cn.chyuan.ai.domain.governance.model.valobj.ModelPricingVO;
import cn.chyuan.ai.domain.governance.service.PricingService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 模型计价管理用例（工单 0085）：直通领域计价服务
 *
 * @author chyuan
 */
@Service
public class AdminPricingService implements IAdminPricingService {

    @Resource
    private PricingService pricingService;

    @Override
    public List<ModelPricingResponseDTO> listPricing() {
        return pricingService.listAll().stream().map(this::toDto).toList();
    }

    @Override
    public ModelPricingResponseDTO getPricing(Long id) {
        return toDto(pricingService.get(id));
    }

    @Override
    public ModelPricingResponseDTO createPricing(ModelPricingRequestDTO requestDTO) {
        return toDto(pricingService.create(toVo(requestDTO)));
    }

    @Override
    public ModelPricingResponseDTO updatePricing(Long id, ModelPricingRequestDTO requestDTO) {
        return toDto(pricingService.update(id, toVo(requestDTO)));
    }

    @Override
    public void deletePricing(Long id) {
        pricingService.delete(id);
    }

    private ModelPricingVO toVo(ModelPricingRequestDTO dto) {
        return ModelPricingVO.builder()
                .model(dto.getModel())
                .inputCostPerM(dto.getInputCostPerM())
                .outputCostPerM(dto.getOutputCostPerM())
                .currency(dto.getCurrency() == null || dto.getCurrency().isBlank() ? "CNY" : dto.getCurrency())
                .enabled(dto.getEnabled() == null ? 1 : dto.getEnabled())
                .remark(dto.getRemark())
                .build();
    }

    private ModelPricingResponseDTO toDto(ModelPricingVO vo) {
        ModelPricingResponseDTO dto = new ModelPricingResponseDTO();
        dto.setId(vo.getId());
        dto.setModel(vo.getModel());
        dto.setInputCostPerM(vo.getInputCostPerM());
        dto.setOutputCostPerM(vo.getOutputCostPerM());
        dto.setCurrency(vo.getCurrency());
        dto.setEnabled(vo.getEnabled());
        dto.setRemark(vo.getRemark());
        dto.setUpdateTime(vo.getUpdateTime() == null ? null
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(vo.getUpdateTime()));
        return dto;
    }
}
