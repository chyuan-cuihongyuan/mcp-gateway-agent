package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IModelPricingRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.ModelPricingVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型计价服务（工单 0085：LiteLLM cost map 口径裁剪）
 *
 * <p>查价语义：模型名精确匹配且 enabled=1 才命中；未命中/停用返回 null
 * （调用侧按「未定价」处理——不阻塞、指标计数）。CRUD 挂审计。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class PricingService {

    private static final String RESOURCE_TYPE = "MODEL_PRICING";

    @Resource
    private IModelPricingRepository repository;

    @Resource
    private IAuditService auditService;

    /** 指标（工单 0086）：未定价计数——切片测试上下文可缺省 */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

    /** 查价：唯一键精确匹配 + 启用态；未定价返回 null */
    public ModelPricingVO findEnabled(String model) {
        if (StringUtils.isBlank(model)) {
            return null;
        }
        ModelPricingVO vo = repository.findByModel(model);
        return vo != null && Integer.valueOf(1).equals(vo.getEnabled()) ? vo : null;
    }

    /**
     * 按模型计价（工单 0086 账本/响应头消费）：
     * 命中返回 6 位舍入成本；未定价/停用返回 null 并计 gateway.pricing.miss（不阻塞）。
     */
    public BigDecimal costOf(String model, Long promptTokens, Long completionTokens) {
        ModelPricingVO pricing = findEnabled(model);
        if (pricing == null) {
            countMiss(model);
            return null;
        }
        return pricing.costOf(promptTokens, completionTokens);
    }

    private void countMiss(String model) {
        io.micrometer.core.instrument.MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("gateway.pricing.miss",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("model", model == null ? "" : model)))
                    .increment();
        }
    }

    public List<ModelPricingVO> listAll() {
        return repository.findAll();
    }

    public ModelPricingVO get(Long id) {
        ModelPricingVO vo = repository.findById(id);
        if (vo == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "计价记录不存在: " + id);
        }
        return vo;
    }

    /** 创建：模型名唯一（冲突结构化拒绝）+ 单价非负校验 */
    public ModelPricingVO create(ModelPricingVO vo) {
        validate(vo, true);
        if (repository.findByModel(vo.getModel()) != null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模型计价已存在: " + vo.getModel());
        }
        Long id = repository.insert(vo);
        vo.setId(id);
        audit("CREATE_PRICING", vo.getModel(), null, vo);
        return vo;
    }

    /** 更新：模型名不可改（计价与账本引用）；留空单价表示置 0 */
    public ModelPricingVO update(Long id, ModelPricingVO vo) {
        ModelPricingVO before = get(id);
        vo.setId(id);
        vo.setModel(before.getModel());
        validate(vo, false);
        repository.update(vo);
        audit("UPDATE_PRICING", vo.getModel(), before, vo);
        return repository.findById(id);
    }

    public void delete(Long id) {
        ModelPricingVO before = get(id);
        repository.deleteById(id);
        audit("DELETE_PRICING", before.getModel(), before, null);
    }

    private void validate(ModelPricingVO vo, boolean forCreate) {
        if (forCreate && StringUtils.isBlank(vo.getModel())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模型名不能为空");
        }
        if (negative(vo.getInputCostPerM()) || negative(vo.getOutputCostPerM())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "单价不能为负数");
        }
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private void audit(String action, String resourceId, ModelPricingVO before, ModelPricingVO after) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action(action)
                .resourceType(RESOURCE_TYPE)
                .resourceId(resourceId)
                .afterJson(after == null ? null : "{\"model\":\"" + after.getModel()
                        + "\",\"input\":" + after.getInputCostPerM()
                        + ",\"output\":" + after.getOutputCostPerM()
                        + ",\"enabled\":" + after.getEnabled() + "}")
                .build());
    }
}
