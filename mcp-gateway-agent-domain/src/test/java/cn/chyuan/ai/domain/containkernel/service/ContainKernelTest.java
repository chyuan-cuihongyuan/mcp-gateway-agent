package cn.chyuan.ai.domain.containkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器生命周期内核测试（工单 1167-1174 FB1-FB8，moby 思想）。
 * 镜像引用/容器创建/四态状态机/端口映射/卷挂载/restart 策略/资源限额/端口组合管线。
 */
class ContainKernelTest {

    @Test
    void imageReference() {
        assertEquals("latest", ImageReferences.parse("nginx").tag(), "tag 缺省 latest");
        assertEquals("1.25", ImageReferences.parse("nginx:1.25").tag());
        assertEquals("redis", ImageReferences.parse("redis").repository());
        ImageReferences digest = ImageReferences.parse("alpine@sha256:" + "a".repeat(64));
        assertEquals("sha256:" + "a".repeat(64), digest.digest(), "digest 形式引用");
        assertThrows(IllegalArgumentException.class, () -> ImageReferences.parse("Nginx"), "大写拒绝");
        assertThrows(IllegalArgumentException.class, () -> ImageReferences.parse(" "), "空名拒绝");
        assertThrows(IllegalArgumentException.class, () -> ImageReferences.parse("nginx@" + "z".repeat(64)), "非法 digest 拒绝");
        assertThrows(IllegalArgumentException.class, () -> ImageReferences.parse("nginx:"), "空 tag 拒绝");
    }

    @Test
    void containerCreate() {
        ContainPort port = ContainPort.inMemory(4, 1024);
        assertThrows(IllegalArgumentException.class, () -> port.create("nginx:latest", "web"), "未知镜像拒绝");
        port.pull("nginx:1.25");
        String id = port.create("nginx:1.25", "web");
        assertTrue(id.startsWith("ctr-"), "唯一容器 id");
        assertEquals("CREATED", port.state("web"), "缺省配置 CREATED 起步");
        assertThrows(IllegalArgumentException.class, () -> port.create("nginx:1.25", "web"), "名称唯一重复拒绝");
        port.pull("nginx");
        port.create("nginx", "default-tag");
        assertEquals(List.of("web", "default-tag"), port.ps(), "创建序列表");
    }

    @Test
    void stateMachine() {
        ContainerStates states = new ContainerStates();
        assertEquals("CREATED", states.state().name());
        states.to("RUNNING");
        states.to("PAUSED");
        states.to("RUNNING");
        states.to("STOPPED");
        assertThrows(IllegalStateException.class, () -> states.to("PAUSED"), "stopped 后 pause 拒绝");
        assertThrows(IllegalStateException.class, () -> states.to("STOPPED"), "重复 stop 拒绝");
        assertThrows(IllegalStateException.class, () -> states.to("CREATED"), "不可回到 created");
        ContainerStates fresh = new ContainerStates();
        assertThrows(IllegalStateException.class, () -> fresh.to("STOPPED"), "created 直接 stop 拒绝");
        assertThrows(IllegalStateException.class, () -> fresh.to("PAUSED"), "created 直接 pause 拒绝");
        assertThrows(IllegalArgumentException.class, () -> fresh.to("ZOMBIE"), "未知状态拒绝");

        ContainPort port = ContainPort.inMemory(4, 1024);
        port.pull("nginx");
        port.create("nginx", "web");
        assertThrows(IllegalStateException.class, () -> port.pause("web"), "created 直接 pause 拒绝");
    }

    @Test
    void portMapping() {
        ContainPort port = ContainPort.inMemory(4, 1024);
        port.pull("nginx");
        port.create("nginx", "web");
        port.create("nginx", "api");
        port.port("web", 8080, 80, "tcp");
        port.port("web", 9090, 53, "udp");
        assertThrows(IllegalArgumentException.class, () -> port.port("web", 0, 80, "tcp"), "非法端口拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.port("web", 8080, 80, "sctp"), "非法协议拒绝");
        port.start("web");
        assertThrows(IllegalStateException.class, () -> port.port("api", 8080, 80, "tcp"), "host 冲突拒绝");
        port.stop("web");
        port.start("api");
        ContainPort host = ContainPort.inMemory(4, 1024);
        host.pull("nginx");
        host.create("nginx", "single");
        host.port("single", 8080, 80, "tcp");
        host.start("single");
        host.stop("single");
        host.start("single");
        assertEquals("RUNNING", host.state("single"), "重启重占端口");
    }

    @Test
    void volumeMounts() {
        VolumeMounts mounts = new VolumeMounts();
        java.util.Map<String, VolumeMounts.Mount> declared = new java.util.LinkedHashMap<>();
        mounts.declare(declared, new VolumeMounts.Mount("data-vol", "/var/lib", false));
        assertThrows(IllegalStateException.class,
                () -> mounts.declare(declared, new VolumeMounts.Mount("other", "/var/lib", true)), "容器内挂载点冲突拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> mounts.declare(declared, new VolumeMounts.Mount("v2", "relative", false)), "容器内路径须 / 起始");
        java.util.Map<String, VolumeMounts.Mount> second = new java.util.LinkedHashMap<>();
        mounts.declare(second, new VolumeMounts.Mount("data-vol", "/data", true));
        assertTrue(mounts.shared("data-vol"), "卷跨容器复用");
        assertTrue(second.get("/data").readonly(), "只读标志保留");
    }

    @Test
    void restartPolicy() {
        RestartPolicies no = new RestartPolicies(new RestartPolicies.Policy("no", 0));
        assertFalse(no.onCrash(), "no 不重启");
        assertThrows(IllegalArgumentException.class, () -> new RestartPolicies.Policy("sometimes", 1), "未知策略拒绝");

        RestartPolicies always = new RestartPolicies(new RestartPolicies.Policy("always", 0));
        assertTrue(always.onCrash());
        assertTrue(always.onCrash());
        assertEquals(2, always.restarts());
        assertEquals(2, always.nextBackoff(), "退避倍增 1→2");
        always.onCleanStart();
        assertEquals(0, always.attempts(), "成功重置计数");
        assertEquals(1, always.nextBackoff());

        RestartPolicies capped = new RestartPolicies(new RestartPolicies.Policy("always", 0));
        for (int i = 0; i < 10; i++) {
            capped.onCrash();
        }
        assertEquals(RestartPolicies.BACKOFF_CAP, capped.nextBackoff(), "退避封顶 60");

        RestartPolicies limited = new RestartPolicies(new RestartPolicies.Policy("on-failure", 2));
        assertTrue(limited.onCrash());
        assertTrue(limited.onCrash());
        assertFalse(limited.onCrash(), "on-failure 限次数后不再重启");
    }

    @Test
    void crashFlowViaPort() {
        ContainPort port = ContainPort.inMemory(4, 1024);
        port.pull("nginx");
        port.create("nginx", "svc");
        port.restartPolicy("svc", "on-failure", 1);
        port.start("svc");
        port.crash("svc");
        assertEquals("RUNNING", port.state("svc"), "on-failure 未达上限自动重启");
        assertEquals(1, port.restarts("svc"));
        port.crash("svc");
        assertEquals("STOPPED", port.state("svc"), "达上限后停机");
        ContainPort noPolicy = ContainPort.inMemory(4, 1024);
        noPolicy.pull("nginx");
        noPolicy.create("nginx", "plain");
        noPolicy.start("plain");
        noPolicy.crash("plain");
        assertEquals("STOPPED", noPolicy.state("plain"), "no 策略不重启");
    }

    @Test
    void portPipeline() {
        ContainPort port = ContainPort.inMemory(2, 1024);
        port.pull("nginx:1.25");
        port.create("nginx:1.25", "gateway");
        port.port("gateway", 80, 8080, "tcp");
        port.mount("gateway", "conf-vol", "/etc/nginx", true);
        port.limits("gateway", 4, 512);
        assertThrows(IllegalStateException.class, () -> port.start("gateway"), "cpu 超限启动拒绝");
        port.limits("gateway", 1, 512);
        port.start("gateway");
        assertEquals("RUNNING", port.state("gateway"));
        assertThrows(IllegalStateException.class, () -> port.limits("gateway", 2, 256), "运行中变更限额拒绝");
        port.stop("gateway");
        assertEquals("STOPPED", port.state("gateway"));
        assertEquals(List.of("address", "weight"), port.endpointsShape(), "envoykernel Endpoint 形状只读联动");
        ContainPort tiny = ContainPort.inMemory(2, 1024);
        tiny.pull("nginx");
        tiny.create("nginx", "a");
        tiny.create("nginx", "b");
        tiny.limits("a", 1.5, 512);
        tiny.limits("b", 1.5, 512);
        tiny.start("a");
        assertThrows(IllegalStateException.class, () -> tiny.start("b"), "累计超 host 容量拒绝");
        assertThrows(IllegalArgumentException.class, () -> tiny.limits("b", -1, 0), "负限额拒绝");
    }
}
