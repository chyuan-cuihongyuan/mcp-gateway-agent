package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.IRoutingRuleRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * tag 路由规则管理服务测试（工单 0160：CRUD 校验与审计）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("tag 路由规则管理服务测试")
public class RoutingRuleAdminServiceTest {

    @Mock
    private IRoutingRuleRepository repository;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private RoutingRuleAdminService service;

    private static RoutingRuleVO rule(String name, String key, String value, String group, Integer priority) {
        return RoutingRuleVO.builder()
                .ruleName(name).tagKey(key).tagValue(value)
                .channelGroupId(group).priority(priority)
                .status(RoutingRuleVO.STATUS_ACTIVE)
                .build();
    }

    @Test
    @DisplayName("创建 — 合法规则落库 + 审计；priority 默认 0")
    public void testCreateOk() {
        when(repository.findByName("gray")).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insert(any(RoutingRuleVO.class))).thenAnswer(inv -> {
            RoutingRuleVO vo = inv.getArgument(0);
            vo.setId(9L);
            return 9L;
        });

        RoutingRuleVO created = service.create(rule("gray", "team", "alpha", "gray-group", null));

        assertEquals(9L, created.getId());
        assertEquals(0, created.getPriority(), "priority 默认 0");
        verify(auditService).record(any());
    }

    @Test
    @DisplayName("创建校验 — 规则名重复 / 字段缺失 / 优先级冲突拒绝")
    public void testCreateValidation() {
        when(repository.findByName("dup")).thenReturn(
                RoutingRuleVO.builder().id(1L).ruleName("dup").build());
        assertThrows(AppException.class, () -> service.create(rule("dup", "team", "alpha", "g", 0)),
                "规则名唯一");

        when(repository.findByName(any())).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of(
                RoutingRuleVO.builder().id(1L).ruleName("old")
                        .tagKey("team").tagValue("alpha").priority(5).build()));
        assertThrows(AppException.class, () -> service.create(rule("new", "team", "alpha", "g", 5)),
                "同标签对同优先级冲突");
        assertDoesNotThrow(() -> service.create(rule("new", "team", "alpha", "g", 6)),
                "不同优先级可存（择序确定）");
        assertThrows(AppException.class, () -> service.create(rule("new", "team", "alpha", null, 1)),
                "渠道组必填");
    }

    @Test
    @DisplayName("更新/删除 — 审计留痕；目标不存在报 METHOD_NOT_FOUND")
    public void testUpdateDelete() {
        when(repository.findById(7L)).thenReturn(RoutingRuleVO.builder()
                .id(7L).ruleName("gray").tagKey("team").tagValue("alpha")
                .channelGroupId("g").priority(0).status(RoutingRuleVO.STATUS_ACTIVE).build());
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByName("gray")).thenReturn(null);
        when(repository.update(any(RoutingRuleVO.class))).thenReturn(true);

        service.update(7L, rule("gray", "team", "beta", "g", 1));
        verify(repository).update(any(RoutingRuleVO.class));
        verify(auditService, atLeastOnce()).record(any());

        service.delete(7L);
        verify(repository).deleteById(7L);

        when(repository.findById(99L)).thenReturn(null);
        assertThrows(AppException.class, () -> service.delete(99L));
    }
}
