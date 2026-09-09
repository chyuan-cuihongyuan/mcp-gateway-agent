package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IModelPricingRepository;
import cn.chyuan.ai.domain.governance.model.valobj.ModelPricingVO;
import cn.chyuan.ai.infrastructure.dao.IModelPricingDao;
import cn.chyuan.ai.infrastructure.dao.po.McpModelPricingPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 模型计价仓储实现（工单 0085）
 *
 * @author chyuan
 */
@Repository
public class ModelPricingRepository implements IModelPricingRepository {

    @Resource
    private IModelPricingDao modelPricingDao;

    @Override
    public Long insert(ModelPricingVO vo) {
        McpModelPricingPO po = toPo(vo);
        modelPricingDao.insert(po);
        return po.getId();
    }

    @Override
    public boolean update(ModelPricingVO vo) {
        return modelPricingDao.update(toPo(vo)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return modelPricingDao.deleteById(id) > 0;
    }

    @Override
    public ModelPricingVO findById(Long id) {
        return toVo(modelPricingDao.queryById(id));
    }

    @Override
    public ModelPricingVO findByModel(String model) {
        return toVo(modelPricingDao.queryByModel(model));
    }

    @Override
    public List<ModelPricingVO> findAll() {
        return modelPricingDao.queryAll().stream().map(this::toVo).toList();
    }

    private McpModelPricingPO toPo(ModelPricingVO vo) {
        return McpModelPricingPO.builder()
                .id(vo.getId())
                .model(vo.getModel())
                .inputCostPerM(vo.getInputCostPerM())
                .outputCostPerM(vo.getOutputCostPerM())
                .currency(vo.getCurrency())
                .enabled(vo.getEnabled())
                .remark(vo.getRemark())
                .build();
    }

    private ModelPricingVO toVo(McpModelPricingPO po) {
        if (po == null) {
            return null;
        }
        return ModelPricingVO.builder()
                .id(po.getId())
                .model(po.getModel())
                .inputCostPerM(po.getInputCostPerM())
                .outputCostPerM(po.getOutputCostPerM())
                .currency(po.getCurrency())
                .enabled(po.getEnabled())
                .remark(po.getRemark())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
