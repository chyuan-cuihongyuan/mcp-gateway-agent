package cn.chyuan.ai.domain.configkernel.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigCenterFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 配置内核门面（工单 0471 BD8）。
 * config-kernel.enabled 默认关：关闭时对既有 configcenter 门面零行为变化；
 * 开启后提供「门面只读联动」——把 configcenter 当前生效值镜像进内核命名空间键
 * （只读不回写），内核侧获得 MVCC/watch/lease 能力。
 */
@Slf4j
@Service
public class ConfigKernelFacade {

    private final ConfigKernelPort kernel;
    private final ConfigCenterFacade configCenter;
    private final boolean enabled;

    public ConfigKernelFacade(ConfigCenterFacade configCenter,
            @Value("${config.kernel.enabled:false}") boolean enabled) {
        this(new ConfigKernelPort.InMemoryConfigKernel(), configCenter, enabled);
    }

    public ConfigKernelFacade(ConfigKernelPort kernel, ConfigCenterFacade configCenter, boolean enabled) {
        this.kernel = kernel;
        this.configCenter = configCenter;
        this.enabled = enabled;
    }

    /** 开关态 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 只读联动：拉取 configcenter 当前生效配置镜像进内核（键 = namespace + '/' + configKey）。
     * 开关关闭时直接透传门面原值、不写内核（零行为变化）；configcenter 无值时返回 null 不镜像。
     */
    public String mirrorFromConfigCenter(String namespace, String configKey) {
        String current = configCenter.resolve(namespace, configKey);
        if (!enabled || current == null) {
            return current;
        }
        String key = namespace + "/" + configKey;
        kernel.put(key, current);
        return current;
    }

    /** 内核写（开关关闭时拒绝，保证默认关语义） */
    public long put(String key, String value) {
        requireEnabled();
        return kernel.put(key, value);
    }

    /** 内核读：优先内核，未命中回退 configcenter 只读 */
    public String get(String namespace, String configKey) {
        if (enabled) {
            String local = kernel.get(namespace + "/" + configKey);
            if (local != null) {
                return local;
            }
        }
        return configCenter.resolve(namespace, configKey);
    }

    /** 内核 watch 前缀订阅 */
    public List<WatchStream.Event> watch(String keyPrefix, long fromRevision) {
        requireEnabled();
        return kernel.watch(keyPrefix, fromRevision);
    }

    /** 租约面 */
    public long grantLease(long ttlMillis) {
        requireEnabled();
        return kernel.grantLease(ttlMillis);
    }

    public List<String> revokeLease(long leaseId) {
        requireEnabled();
        return kernel.revokeLease(leaseId);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("config-kernel 未开启（config.kernel.enabled=false 默认关）");
        }
    }
}
