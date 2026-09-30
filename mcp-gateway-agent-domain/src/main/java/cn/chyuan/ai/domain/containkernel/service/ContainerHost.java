package cn.chyuan.ai.domain.containkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 容器宿主组合实现（工单 1174 FB8，moby 思想）。
 * start 准入序：状态迁移←资源限额←端口占用←restart 成功清计数；stop 释放端口。
 */
public final class ContainerHost implements ContainPort {

    /** 容器记录 */
    private static final class Container {
        final String id;
        final String name;
        final ImageReferences image;
        final ContainerStates states = new ContainerStates();
        final Map<String, PortMappings.Binding> declaredPorts = new LinkedHashMap<>();
        final Map<String, VolumeMounts.Mount> mounts = new LinkedHashMap<>();
        RestartPolicies restart = new RestartPolicies(new RestartPolicies.Policy("no", 0));
        ResourceLimits.Limits limits = new ResourceLimits.Limits(0, 0);

        Container(String id, String name, ImageReferences image) {
            this.id = id;
            this.name = name;
            this.image = image;
        }
    }

    private final Map<String, Container> byName = new LinkedHashMap<>();
    private final Set<String> images = new LinkedHashSet<>();
    private final PortMappings ports = new PortMappings();
    private final VolumeMounts volumes = new VolumeMounts();
    private final ResourceLimits capacity;
    private long idCounter;

    public ContainerHost(double hostCpus, long hostMemoryBytes) {
        capacity = new ResourceLimits(hostCpus, hostMemoryBytes);
    }

    @Override
    public void pull(String imageRef) {
        images.add(ImageReferences.parse(imageRef).key());
    }

    @Override
    public String create(String imageRef, String name) {
        ImageReferences reference = ImageReferences.parse(imageRef);
        if (!images.contains(reference.key())) {
            throw new IllegalArgumentException("未知镜像: " + reference.key());
        }
        if (name == null || name.isBlank() || byName.containsKey(name)) {
            throw new IllegalArgumentException("容器名空或重复: " + name);
        }
        Container container = new Container("ctr-" + ++idCounter, name, reference);
        byName.put(name, container);
        return container.id;
    }

    @Override
    public void port(String name, int hostPort, int containerPort, String protocol) {
        Container container = require(name);
        assertNotRunning(container, "端口映射");
        ports.declare(container.declaredPorts, new PortMappings.Binding(hostPort, containerPort, protocol));
    }

    @Override
    public void mount(String name, String source, String containerPath, boolean readonly) {
        Container container = require(name);
        assertNotRunning(container, "卷挂载");
        volumes.declare(container.mounts, new VolumeMounts.Mount(source, containerPath, readonly));
    }

    @Override
    public void restartPolicy(String name, String type, int maxRetry) {
        Container container = require(name);
        assertNotRunning(container, "restart 策略");
        container.restart = new RestartPolicies(new RestartPolicies.Policy(type, maxRetry));
    }

    @Override
    public void limits(String name, double cpu, long memoryBytes) {
        Container container = require(name);
        if (container.states.state() == ContainerStates.State.RUNNING
                || container.states.state() == ContainerStates.State.PAUSED) {
            throw new IllegalStateException("运行中不可变更限额: " + name);
        }
        container.limits = new ResourceLimits.Limits(cpu, memoryBytes);
    }

    @Override
    public void start(String name) {
        Container container = require(name);
        capacity.admit(runningCpus(), runningMemory(), container.limits);
        ports.checkAll(container.name, container.declaredPorts);
        container.states.to("RUNNING");
        ports.occupyAll(container.name, container.declaredPorts);
        container.restart.onCleanStart();
    }

    @Override
    public void stop(String name) {
        Container container = require(name);
        container.states.to("STOPPED");
        ports.releaseAll(container.name);
    }

    @Override
    public void pause(String name) {
        require(name).states.to("PAUSED");
    }

    @Override
    public void unpause(String name) {
        require(name).states.to("RUNNING");
    }

    @Override
    public String state(String name) {
        return require(name).states.state().name();
    }

    @Override
    public List<String> ps() {
        return List.copyOf(byName.keySet());
    }

    @Override
    public void crash(String name) {
        Container container = require(name);
        if (container.states.state() != ContainerStates.State.RUNNING) {
            throw new IllegalStateException("仅运行中容器可异常退出: " + name);
        }
        if (container.restart.onCrash()) {
            ports.occupyAll(container.name, container.declaredPorts);
        } else {
            container.states.to("STOPPED");
            ports.releaseAll(container.name);
        }
    }

    @Override
    public int restarts(String name) {
        return require(name).restart.restarts();
    }

    @Override
    public long nextBackoff(String name) {
        return require(name).restart.nextBackoff();
    }

    @Override
    public List<String> endpointsShape() {
        return List.of("address", "weight");
    }

    private Container require(String name) {
        Container container = byName.get(name);
        if (container == null) {
            throw new IllegalArgumentException("未知容器: " + name);
        }
        return container;
    }

    private void assertNotRunning(Container container, String what) {
        if (container.states.state() == ContainerStates.State.RUNNING
                || container.states.state() == ContainerStates.State.PAUSED) {
            throw new IllegalStateException("运行中不可变更" + what + ": " + container.name);
        }
    }

    private double runningCpus() {
        double sum = 0;
        for (Container container : byName.values()) {
            if (container.states.state() == ContainerStates.State.RUNNING && !container.limits.unlimited()) {
                sum += container.limits.cpu();
            }
        }
        return sum;
    }

    private long runningMemory() {
        long sum = 0;
        for (Container container : byName.values()) {
            if (container.states.state() == ContainerStates.State.RUNNING && !container.limits.unlimited()) {
                sum += container.limits.memoryBytes();
            }
        }
        return sum;
    }

    /** 卷使用统计（FB5 跨容器复用校验用） */
    int volumeUsage(String volume) {
        return volumes.usage(volume);
    }
}
