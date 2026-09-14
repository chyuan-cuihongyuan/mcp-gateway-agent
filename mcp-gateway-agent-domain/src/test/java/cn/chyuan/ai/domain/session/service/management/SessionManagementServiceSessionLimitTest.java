package cn.chyuan.ai.domain.session.service.management;

import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话并发上限护栏（SELFLOOP3 loop-314，工单 0426/0427）
 */
class SessionManagementServiceSessionLimitTest {

    private SessionManagementService serviceWithLimit(int maxActive) {
        SessionManagementService service = new SessionManagementService();
        ReflectionTestUtils.setField(service, "maxActiveSessions", maxActive);
        return service;
    }

    @Test
    void sessionCreationRejectedWhenLimitReached() {
        SessionManagementService service = serviceWithLimit(2);
        service.createSession("g1", "key");
        service.createSession("g1", "key");

        assertThatThrownBy(() -> service.createSession("g1", "key"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getInfo()).contains("会话数已达上限"));
    }

    @Test
    void sessionCanBeCreatedAfterRemoval() {
        SessionManagementService service = serviceWithLimit(1);
        SessionConfigVO first = service.createSession("g1", "key");
        service.removeSession(first.getSessionId());

        assertThatCode(() -> service.createSession("g1", "key")).doesNotThrowAnyException();
    }

    @Test
    void zeroLimitDisablesGuard() {
        SessionManagementService service = serviceWithLimit(0);
        for (int i = 0; i < 5; i++) {
            service.createSession("g1", "key");
        }
        // 0 = 关闭护栏，5 个会话全部建立成功
        assertThatCode(() -> service.createSession("g1", "key")).doesNotThrowAnyException();
    }
}
