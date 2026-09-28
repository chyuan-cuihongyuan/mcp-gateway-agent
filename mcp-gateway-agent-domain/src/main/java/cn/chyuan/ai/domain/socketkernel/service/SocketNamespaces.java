package cn.chyuan.ai.domain.socketkernel.service;

import java.util.HashSet;
import java.util.Set;

/**
 * 命名空间（工单 0960 ED1，socket.io 思想）。
 * namespace 注册隔离/未注册拒绝/跨 ns 房间不可见。
 */
public final class SocketNamespaces {

    private final Set<String> registered = new HashSet<>();

    /** 注册命名空间：以 / 起头，重复幂等 */
    public void register(String name) {
        if (!name.startsWith("/")) {
            throw new IllegalArgumentException("命名空间须以 / 起头: " + name);
        }
        registered.add(name);
    }

    /** 校验可连接：未注册拒绝 */
    public void require(String name) {
        if (!registered.contains(name)) {
            throw new IllegalArgumentException("未注册命名空间: " + name);
        }
    }

    public boolean exists(String name) {
        return registered.contains(name);
    }

    public int count() {
        return registered.size();
    }
}
