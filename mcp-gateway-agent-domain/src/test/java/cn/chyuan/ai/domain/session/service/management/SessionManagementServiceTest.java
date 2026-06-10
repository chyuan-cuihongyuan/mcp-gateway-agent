package cn.chyuan.ai.domain.session.service.management;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionMetaRepository;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.SessionMetaVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SessionManagementServiceTest {

    private final SessionManagementService service = new SessionManagementService();
    private final ISessionMetaRepository metaRepository = mock(ISessionMetaRepository.class);

    SessionManagementServiceTest() {
        ReflectionTestUtils.setField(service, "sessionMetaRepository", metaRepository);
        ReflectionTestUtils.setField(service, "sessionTimeoutMinutes", 30L);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void createSessionPersistsRedisMetadata() throws Exception {
        SessionConfigVO session = service.createSession("gateway_001", "secret-key");

        ArgumentCaptor<SessionMetaVO> metaCaptor = ArgumentCaptor.forClass(SessionMetaVO.class);
        verify(metaRepository).save(metaCaptor.capture(), eq(Duration.ofMinutes(30)));
        assertThat(metaCaptor.getValue().getSessionId()).isEqualTo(session.getSessionId());
        assertThat(metaCaptor.getValue().getGatewayId()).isEqualTo("gateway_001");
        assertThat(metaCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(metaCaptor.getValue().getApiKeyHash()).isEqualTo(sha256("secret-key"));
        assertThat(metaCaptor.getValue().getApiKeyHash()).doesNotContain("secret-key");
    }

    @Test
    void getSessionTouchesMetadataAndRemoveDeletesMetadata() throws Exception {
        SessionConfigVO session = service.createSession("gateway_001", "secret-key");

        assertThat(service.getSession(session.getSessionId())).isSameAs(session);
        verify(metaRepository).touch(session.getSessionId(), Duration.ofMinutes(30));

        service.removeSession(session.getSessionId());
        verify(metaRepository).delete(session.getSessionId());
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
