package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 治理事件发布——无操作实现（工单 0050）
 *
 * <p>0051 的 webhook 投递实现将以 @Primary/@ConditionalOnMissingBean 方式接管。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class NoopGovernanceEventPublisher implements IGovernanceEventPublisher {

    @Override
    public void publish(String type, Map<String, Object> payload) {
        log.debug("治理事件（无操作投递）type={} payload={}", type, payload);
    }
}
