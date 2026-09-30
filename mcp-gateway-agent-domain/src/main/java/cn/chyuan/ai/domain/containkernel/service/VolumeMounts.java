package cn.chyuan.ai.domain.containkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卷挂载（工单 1171 FB5，moby 思想）。
 * volume/binds 声明/容器内挂载点冲突拒绝/卷跨容器复用/只读标志保留。
 */
public final class VolumeMounts {

    /** 挂载：卷名或宿主路径 + 容器内挂载点 + 只读 */
    public record Mount(String source, String containerPath, boolean readonly) {

        public Mount {
            if (source == null || source.isBlank() || containerPath == null || !containerPath.startsWith("/")) {
                throw new IllegalArgumentException("挂载源与容器内路径（须 / 起始）不合法");
            }
        }
    }

    /** 卷使用计数：卷名 → 使用容器数（跨容器复用） */
    private final Map<String, Integer> volumeUsage = new LinkedHashMap<>();

    /** 声明挂载（容器级）：同容器内挂载点冲突拒绝 */
    public void declare(Map<String, Mount> into, Mount mount) {
        if (into.containsKey(mount.containerPath())) {
            throw new IllegalStateException("容器内挂载点冲突: " + mount.containerPath());
        }
        into.put(mount.containerPath(), mount);
        if (!mount.source().startsWith("/")) {
            volumeUsage.merge(mount.source(), 1, Integer::sum);
        }
    }

    /** 卷是否被多容器复用 */
    public boolean shared(String volume) {
        return volumeUsage.getOrDefault(volume, 0) > 1;
    }

    public int usage(String volume) {
        return volumeUsage.getOrDefault(volume, 0);
    }
}
