package cn.chyuan.ai.domain.containkernel.service;

import java.util.List;

/**
 * 容器生命周期端口（工单 1174 FB8，moby 思想）。
 * create·start·stop·ps 入口统一编排：镜像引用·创建·四态状态机·端口映射·
 * 卷挂载·restart 策略·资源限额组合管线/envoykernel 端点形状只读联动
 * （形状键与 EnvoyCluster.Endpoint 字段对齐，不 import envoykernel）/
 * contain-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ContainPort {

    /** 镜像入库（FB1） */
    void pull(String imageRef);

    /** 创建容器：返回 id（FB2） */
    String create(String imageRef, String name);

    /** 声明端口映射（FB4） */
    void port(String name, int hostPort, int containerPort, String protocol);

    /** 声明卷挂载（FB5） */
    void mount(String name, String source, String containerPath, boolean readonly);

    /** restart 策略（FB6） */
    void restartPolicy(String name, String type, int maxRetry);

    /** 资源限额（FB7） */
    void limits(String name, double cpu, long memoryBytes);

    void start(String name);

    void stop(String name);

    void pause(String name);

    void unpause(String name);

    String state(String name);

    /** 容器名列表（创建序） */
    List<String> ps();

    /** 注入异常退出：restart 策略判定是否自动重启（FB6） */
    void crash(String name);

    int restarts(String name);

    /** 下次重启退避 ticks（FB6） */
    long nextBackoff(String name);

    /** envoykernel 端点形状只读联动（Endpoint: address/weight） */
    List<String> endpointsShape();

    static ContainPort inMemory() {
        return new ContainerHost(4, 8L * 1024 * 1024 * 1024);
    }

    static ContainPort inMemory(double hostCpus, long hostMemoryBytes) {
        return new ContainerHost(hostCpus, hostMemoryBytes);
    }
}
