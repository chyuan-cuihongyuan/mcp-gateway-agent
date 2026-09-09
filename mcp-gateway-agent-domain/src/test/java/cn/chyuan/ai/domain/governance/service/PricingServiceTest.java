package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IModelPricingRepository;
import cn.chyuan.ai.domain.governance.model.valobj.ModelPricingVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

/**
 * 模型计价服务测试（工单 0085：查价语义 / CRUD 校验 / 审计 / 计价计算）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模型计价服务测试")
class PricingServiceTest {

    @Mock
    private IModelPricingRepository repository;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private PricingService service;

    private ModelPricingVO vo(String model, String in, String out, int enabled) {
        return ModelPricingVO.builder()
                .model(model)
                .inputCostPerM(in == null ? null : new BigDecimal(in))
                .outputCostPerM(out == null ? null : new BigDecimal(out))
                .currency("CNY")
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("查价：启用命中；停用/未建/空名 → null（未定价语义）")
    void findEnabledSemantics() {
        Mockito.when(repository.findByModel("deepseek-chat")).thenReturn(vo("deepseek-chat", "2", "8", 1));
        Mockito.when(repository.findByModel("qwen-plus")).thenReturn(vo("qwen-plus", "0.8", "2", 0));

        Assertions.assertNotNull(service.findEnabled("deepseek-chat"));
        Assertions.assertNull(service.findEnabled("qwen-plus"), "停用计价按未定价处理");
        Assertions.assertNull(service.findEnabled("not-priced"));
        Assertions.assertNull(service.findEnabled(" "));
        Assertions.assertNull(service.findEnabled(null));
    }

    @Test
    @DisplayName("创建：模型名冲突结构化拒绝；负单价拒绝；成功挂审计")
    void createValidation() {
        Mockito.when(repository.findByModel("dup")).thenReturn(vo("dup", "1", "1", 1));
        Assertions.assertThrows(AppException.class, () -> service.create(vo("dup", "1", "1", 1)));
        Assertions.assertThrows(AppException.class, () -> service.create(vo("m", "-1", "1", 1)));
        Assertions.assertThrows(AppException.class, () -> service.create(vo(" ", "1", "1", 1)));

        Mockito.when(repository.insert(Mockito.any())).thenReturn(9L);
        ModelPricingVO created = service.create(vo("new-model", "2", "8", 1));
        Assertions.assertEquals(9L, created.getId());
        Mockito.verify(auditService).record(Mockito.argThat(cmd -> "CREATE_PRICING".equals(cmd.getAction())));
    }

    @Test
    @DisplayName("更新：模型名不可改（沿用库中原名）")
    void updateKeepsModelName() {
        Mockito.when(repository.findById(5L)).thenReturn(ModelPricingVO.builder()
                .id(5L).model("original").inputCostPerM(BigDecimal.ONE).outputCostPerM(BigDecimal.ONE)
                .currency("CNY").enabled(1).build());
        ModelPricingVO request = vo("renamed", "3", "9", 1);
        service.update(5L, request);
        Mockito.verify(repository).update(Mockito.argThat(v -> "original".equals(v.getModel())));
    }

    @Test
    @DisplayName("计价计算：输入/输出分别计价求和，6 位舍入；null 按 0")
    void costOfCalculation() {
        ModelPricingVO pricing = vo("m", "2", "8", 1);
        // (2*1000 + 8*500) / 1_000_000 = 0.006
        Assertions.assertEquals(new BigDecimal("0.006000"), pricing.costOf(1000L, 500L));
        Assertions.assertEquals(BigDecimal.ZERO.compareTo(pricing.costOf(null, null)), 0);
        Assertions.assertEquals(new BigDecimal("0.000016"), vo("m2", "0.016", null, 1).costOf(1000L, 0L));
    }

    @Test
    @DisplayName("按模型计价（0086）：命中返回成本；未定价/停用返回 null（miss 计数不抛错）")
    void costOfByModel() {
        Mockito.when(repository.findByModel("deepseek-chat")).thenReturn(vo("deepseek-chat", "2", "8", 1));
        Mockito.when(repository.findByModel("qwen-plus")).thenReturn(vo("qwen-plus", "0.8", "2", 0));

        Assertions.assertEquals(new BigDecimal("0.006000"), service.costOf("deepseek-chat", 1000L, 500L));
        Assertions.assertNull(service.costOf("qwen-plus", 1000L, 500L), "停用计价按未定价处理");
        Assertions.assertNull(service.costOf("not-priced", 1000L, 500L));
        Assertions.assertNull(service.costOf(null, 1000L, 500L));
    }
}
