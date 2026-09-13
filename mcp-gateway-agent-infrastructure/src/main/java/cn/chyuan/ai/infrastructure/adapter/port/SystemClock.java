package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.llmcache.service.PrefixCacheStore;
import org.springframework.stereotype.Component;

/**
 * 系统时钟（前缀缓存 TTL 用）：生产走系统时钟，测试注入固定时钟。
 *
 * @author chyuan
 */
@Component
public class SystemClock implements PrefixCacheStore.Clock {

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }
}
