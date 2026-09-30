package cn.chyuan.ai.domain.containkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端口映射（工单 1170 FB4，moby 思想）。
 * host:container+协议映射/host 冲突拒绝/停止释放端口/重启重占。
 */
public final class PortMappings {

    /** 绑定：host 端口 + 容器端口 + 协议 */
    public record Binding(int hostPort, int containerPort, String protocol) {

        public Binding {
            if (hostPort <= 0 || hostPort > 65535 || containerPort <= 0 || containerPort > 65535) {
                throw new IllegalArgumentException("端口须在 1-65535: " + hostPort + ":" + containerPort);
            }
            if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
                throw new IllegalArgumentException("协议须 tcp/udp: " + protocol);
            }
        }

        String occupancy() {
            return hostPort + "/" + protocol;
        }
    }

    /** 全局占用表：占用键 → 持有容器 */
    private final Map<String, String> occupied = new LinkedHashMap<>();

    /** 声明绑定（容器级） */
    public void declare(Map<String, Binding> into, Binding binding) {
        String occupancy = binding.occupancy();
        String holder = occupied.get(occupancy);
        if (holder != null) {
            throw new IllegalStateException("host 端口冲突: " + occupancy + " 已被 " + holder + " 占用");
        }
        into.put(occupancy, binding);
    }

    /** 启动前纯校验：不占用，仅预检冲突 */
    public void checkAll(String containerName, Map<String, Binding> declared) {
        for (Map.Entry<String, Binding> entry : declared.entrySet()) {
            String holder = occupied.get(entry.getKey());
            if (holder != null && !holder.equals(containerName)) {
                throw new IllegalStateException("host 端口冲突: " + entry.getKey() + " 已被 " + holder + " 占用");
            }
        }
    }

    /** 启动占用：声明逐条入全局表（他人已占即拒绝） */
    public void occupyAll(String containerName, Map<String, Binding> declared) {
        for (Map.Entry<String, Binding> entry : declared.entrySet()) {
            String holder = occupied.get(entry.getKey());
            if (holder != null && !holder.equals(containerName)) {
                throw new IllegalStateException("host 端口冲突: " + entry.getKey() + " 已被 " + holder + " 占用");
            }
            occupied.put(entry.getKey(), containerName);
        }
    }

    /** 停止释放 */
    public void releaseAll(String containerName) {
        occupied.entrySet().removeIf(e -> e.getValue().equals(containerName));
    }

    public int occupiedCount() {
        return occupied.size();
    }
}
