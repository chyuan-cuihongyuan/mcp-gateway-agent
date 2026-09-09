package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.ModelPricingVO;

import java.util.List;

/**
 * 模型计价仓储端口（工单 0085）
 *
 * @author chyuan
 */
public interface IModelPricingRepository {

    Long insert(ModelPricingVO vo);

    boolean update(ModelPricingVO vo);

    boolean deleteById(Long id);

    ModelPricingVO findById(Long id);

    /** 按模型名精确查（唯一键；不存在返回 null） */
    ModelPricingVO findByModel(String model);

    List<ModelPricingVO> findAll();
}
