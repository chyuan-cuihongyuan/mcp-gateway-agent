package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminExternalAttachService;
import cn.chyuan.ai.api.dto.ExternalAttachResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachTestResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachUpsertRequestDTO;
import cn.chyuan.ai.api.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部挂接 admin 接口测试（工单 0021：委托编排与响应装配；
 * JWT 认证/角色约束由 AdminJwtAuthFilter 统一生效，见其过滤器测试）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("外部挂接 admin 接口测试")
class AdminExternalAttachControllerTest {

    @Mock
    private IAdminExternalAttachService adminExternalAttachService;

    private AdminExternalAttachController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminExternalAttachController();
        org.springframework.test.util.ReflectionTestUtils
                .setField(controller, "adminExternalAttachService", adminExternalAttachService);
    }

    private ExternalAttachUpsertRequestDTO request() {
        return ExternalAttachUpsertRequestDTO.builder()
                .gatewayId("gateway_business")
                .attachName("ext01")
                .transportType("STREAMABLE_HTTP")
                .endpoint("http://127.0.0.1:8099/api-gateway/upstream/mcp")
                .build();
    }

    private ExternalAttachResponseDTO response(Long id) {
        return ExternalAttachResponseDTO.builder()
                .id(id)
                .gatewayId("gateway_business")
                .attachName("ext01")
                .transportType("STREAMABLE_HTTP")
                .connectStatus("CONNECTED")
                .toolCount(3)
                .build();
    }

    @Test
    @DisplayName("清单 — 透传网关参数并返回连接状态与工具数")
    void listDelegatesWithGatewayId() {
        when(adminExternalAttachService.listByGateway("gateway_business")).thenReturn(List.of(response(1L)));

        Response<List<ExternalAttachResponseDTO>> response = controller.list("gateway_business");

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getConnectStatus()).isEqualTo("CONNECTED");
        assertThat(response.getData().get(0).getToolCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("创建/更新/删除 — 委托 case 服务（id 语义正确）")
    void crudDelegatesToCaseService() {
        when(adminExternalAttachService.createAttach(any())).thenReturn(response(9L));
        when(adminExternalAttachService.updateAttach(eq(9L), any())).thenReturn(response(9L));

        assertThat(controller.create(request()).getData().getId()).isEqualTo(9L);
        assertThat(controller.update(9L, request()).getData().getId()).isEqualTo(9L);

        controller.delete(9L);
        verify(adminExternalAttachService).deleteAttach(9L);
    }

    @Test
    @DisplayName("探活 — 连接失败结构化返回（可观测错误态）")
    void testReturnsObservableFailure() {
        when(adminExternalAttachService.testAttach(any())).thenReturn(
                ExternalAttachTestResponseDTO.builder().connected(false).toolCount(0)
                        .error("Connection refused").build());
        when(adminExternalAttachService.testAttachById(9L)).thenReturn(
                ExternalAttachTestResponseDTO.builder().connected(true).toolCount(5).build());

        ExternalAttachTestResponseDTO byConfig = controller.test(request()).getData();
        assertThat(byConfig.getConnected()).isFalse();
        assertThat(byConfig.getError()).contains("Connection refused");

        ExternalAttachTestResponseDTO byId = controller.testById(9L).getData();
        assertThat(byId.getConnected()).isTrue();
        assertThat(byId.getToolCount()).isEqualTo(5);
    }
}
