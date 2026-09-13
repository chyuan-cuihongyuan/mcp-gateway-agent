package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.configcenter.service.EnvelopeCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地密钥环（工单 0254 AG4）：KEK 口令自配置供给（{@code config.center.kek-secret}，
 * 默认开发占位口令——生产必须覆盖且妥善保管），不引入外部 KMS。
 *
 * @author chyuan
 */
@Component
public class LocalKeyRing implements EnvelopeCipher.KeyRing {

    @Value("${config.center.kek-secret:dev-only-kek-change-me}")
    private String kekSecret;

    @Override
    public String kekSecret() {
        return kekSecret;
    }
}
