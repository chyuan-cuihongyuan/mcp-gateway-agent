package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService;
import cn.chyuan.ai.domain.governance.adapter.repository.IModelPricingRepository;
import org.springframework.stereotype.Component;

/**
 * 计价条目探测端口实现（工单 0281 AJ5）：复用既有 IModelPricingRepository 探测条目存在性；
 * 仓储缺省（切片测试）视为不存在→警告不阻断。
 *
 * @author chyuan
 */
@Component
public class ModelPricingLinkPort implements ModelCatalogService.PricingEntryPort {

    private final IModelPricingRepository pricingRepository;

    public ModelPricingLinkPort(@org.springframework.beans.factory.annotation.Autowired(required = false)
            IModelPricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
    }

    @Override
    public boolean exists(long pricingEntryId) {
        return pricingRepository != null && pricingRepository.findById(pricingEntryId) != null;
    }
}
