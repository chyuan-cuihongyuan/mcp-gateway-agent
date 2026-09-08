package cn.chyuan.ai.domain.externalattach.service;

import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部挂接配置管理服务测试（工单 0021：校验边界 + 审计脱敏 + 键不可变）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("外部挂接管理服务测试")
class ExternalAttachAdminServiceTest {

    @Mock
    private IExternalAttachRepository repository;

    @Mock
    private IAuditService auditService;

    private ExternalAttachAdminService service;

    @BeforeEach
    void setUp() {
        service = new ExternalAttachAdminService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        lenient().when(repository.insert(any())).thenReturn(1L);
    }

    private ExternalAttachVO httpAttach() {
        return ExternalAttachVO.builder()
                .gatewayId("gateway_business")
                .attachName("ext01")
                .transportType(ExternalAttachVO.TRANSPORT_STREAMABLE_HTTP)
                .endpoint("http://127.0.0.1:8099/api-gateway/upstream/mcp")
                .apiKey("vk-secret")
                .build();
    }

    private ExternalAttachVO stdioAttach() {
        return ExternalAttachVO.builder()
                .gatewayId("gateway_business")
                .attachName("localStdio")
                .transportType(ExternalAttachVO.TRANSPORT_STDIO)
                .command("node")
                .args("[\"server.js\"]")
                .env("{\"FOO\":\"bar\"}")
                .build();
    }

    @Test
    @DisplayName("创建校验 — attachName 非法 / 传输参数缺失 / SSE 拒绝")
    void createValidatesNamingAndTransportParameters() {
        assertThatThrownBy(() -> service.create(httpAttach().toBuilder().attachName("1bad name").build()))
                .isInstanceOf(AppException.class).hasMessageContaining("attachName非法");
        assertThatThrownBy(() -> service.create(httpAttach().toBuilder().endpoint("ftp://x").build()))
                .isInstanceOf(AppException.class).hasMessageContaining("endpoint");
        assertThatThrownBy(() -> service.create(httpAttach().toBuilder().transportType("SSE").build()))
                .isInstanceOf(AppException.class).hasMessageContaining("SSE");
        assertThatThrownBy(() -> service.create(stdioAttach().toBuilder().command(null).build()))
                .isInstanceOf(AppException.class).hasMessageContaining("command");
        assertThatThrownBy(() -> service.create(stdioAttach().toBuilder().args("{bad").build()))
                .isInstanceOf(AppException.class).hasMessageContaining("args");
        assertThatThrownBy(() -> service.create(stdioAttach().toBuilder().env("[\"bad\"]").build()))
                .isInstanceOf(AppException.class).hasMessageContaining("env");
        assertThatThrownBy(() -> service.create(httpAttach().toBuilder().requestTimeoutMs(500).build()))
                .isInstanceOf(AppException.class).hasMessageContaining("requestTimeoutMs");
        assertThatThrownBy(() -> service.create(httpAttach().toBuilder().status(2).build()))
                .isInstanceOf(AppException.class).hasMessageContaining("status");
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("创建 — 默认值填充 + 审计快照凭证脱敏")
    void createNormalizesDefaultsAndMasksCredentialInAudit() {
        service.create(httpAttach());

        ArgumentCaptor<ExternalAttachVO> savedCaptor = ArgumentCaptor.forClass(ExternalAttachVO.class);
        verify(repository).insert(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getRequestTimeoutMs()).isEqualTo(30_000);
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(1);

        ArgumentCaptor<AuditCommandEntity> auditCaptor = ArgumentCaptor.forClass(AuditCommandEntity.class);
        verify(auditService).record(auditCaptor.capture());
        AuditCommandEntity audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("CREATE_ATTACH");
        assertThat(audit.getResourceType()).isEqualTo("EXTERNAL_ATTACH");
        assertThat(audit.getResourceId()).isEqualTo("gateway_business/ext01");
        assertThat(audit.getAfterJson()).contains("****").doesNotContain("vk-secret");
    }

    @Test
    @DisplayName("更新 — 网关与挂接名强制回填存量值（工具前缀契约不可变）")
    void updateKeepsGatewayAndAttachNameImmutable() {
        ExternalAttachVO existing = httpAttach();
        existing.setId(7L);
        when(repository.findById(7L)).thenReturn(existing);

        ExternalAttachVO request = stdioAttach();
        request.setGatewayId("other_gateway");
        request.setAttachName("renamed");
        service.update(7L, request);

        ArgumentCaptor<ExternalAttachVO> savedCaptor = ArgumentCaptor.forClass(ExternalAttachVO.class);
        verify(repository).update(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getGatewayId()).isEqualTo("gateway_business");
        assertThat(savedCaptor.getValue().getAttachName()).isEqualTo("ext01");
        assertThat(savedCaptor.getValue().getTransportType()).isEqualTo(ExternalAttachVO.TRANSPORT_STDIO);
    }

    @Test
    @DisplayName("更新/删除 — 不存在的挂接返回 NOT_FOUND")
    void updateOrDeleteMissingAttachThrowsNotFound() {
        when(repository.findById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(404L, httpAttach()))
                .isInstanceOf(AppException.class).hasMessageContaining("挂接配置不存在");
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(AppException.class).hasMessageContaining("挂接配置不存在");
    }

    @Test
    @DisplayName("删除 — 审计留痕 before 快照含脱敏凭证")
    void deleteRecordsAuditWithMaskedSnapshot() {
        ExternalAttachVO existing = httpAttach();
        existing.setId(7L);
        when(repository.findById(7L)).thenReturn(existing);
        when(repository.deleteById(7L)).thenReturn(true);

        service.delete(7L);

        ArgumentCaptor<AuditCommandEntity> auditCaptor = ArgumentCaptor.forClass(AuditCommandEntity.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("DELETE_ATTACH");
        assertThat(auditCaptor.getValue().getBeforeJson()).contains("****").doesNotContain("vk-secret");
    }

    @Test
    @DisplayName("清单 — 按网关透传仓储结果")
    void listByGatewayDelegatesToRepository() {
        when(repository.findByGatewayId("gateway_business")).thenReturn(List.of(httpAttach()));

        assertThat(service.listByGateway("gateway_business")).hasSize(1);
    }

    @Test
    @DisplayName("渠道化字段（0047）— weight/priority 默认 1/0，authType 按 apiKey 有无推导")
    void channelFieldsNormalized() {
        when(repository.insert(any(ExternalAttachVO.class))).thenReturn(1L);
        ExternalAttachVO vo = httpAttach();
        vo.setWeight(null);
        vo.setPriority(null);
        vo.setAuthType(null);

        service.create(vo);

        ArgumentCaptor<ExternalAttachVO> captor = ArgumentCaptor.forClass(ExternalAttachVO.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getWeight()).isEqualTo(1);
        assertThat(captor.getValue().getPriority()).isEqualTo(0);
        assertThat(captor.getValue().getAuthType()).isEqualTo(ExternalAttachVO.AUTH_TYPE_BEARER);
    }

    @Test
    @DisplayName("渠道三态（0047）— status=2 自动禁用不可经 admin API 设置")
    void autoDisabledNotAdminSettable() {
        ExternalAttachVO vo = httpAttach();
        vo.setStatus(ExternalAttachVO.STATUS_AUTO_DISABLED);

        assertThatThrownBy(() -> service.create(vo))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("自动禁用");
    }

    @Test
    @DisplayName("渠道化字段校验（0047）— weight>=1、authType 枚举、authConfig 必须 JSON 对象")
    void channelFieldValidation() {
        ExternalAttachVO badWeight = httpAttach();
        badWeight.setWeight(0);
        assertThatThrownBy(() -> service.create(badWeight))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("weight");

        ExternalAttachVO badAuth = httpAttach();
        badAuth.setAuthType("DIGEST");
        assertThatThrownBy(() -> service.create(badAuth))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("authType");

        ExternalAttachVO badConfig = httpAttach();
        badConfig.setAuthType(ExternalAttachVO.AUTH_TYPE_HEADER);
        badConfig.setAuthConfig("not-json");
        assertThatThrownBy(() -> service.create(badConfig))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("authConfig");
    }
}
