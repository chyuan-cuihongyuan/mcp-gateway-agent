package cn.chyuan.ai.domain.containkernel.service;

/**
 * 资源限额（工单 1173 FB7，moby 思想）。
 * cpu/memory 限额声明/超 host 容量启动拒绝/运行中变更拒绝/无限额默认放行。
 */
public final class ResourceLimits {

    /** 限额：cpu 核数 + 内存字节；0 = 不限额 */
    public record Limits(double cpu, long memoryBytes) {

        public Limits {
            if (cpu < 0 || memoryBytes < 0) {
                throw new IllegalArgumentException("限额不能为负");
            }
        }

        public boolean unlimited() {
            return cpu == 0 && memoryBytes == 0;
        }
    }

    private final double hostCpus;
    private final long hostMemoryBytes;

    public ResourceLimits(double hostCpus, long hostMemoryBytes) {
        if (hostCpus <= 0 || hostMemoryBytes <= 0) {
            throw new IllegalArgumentException("host 容量必须为正");
        }
        this.hostCpus = hostCpus;
        this.hostMemoryBytes = hostMemoryBytes;
    }

    /** 启动准入：运行中已占用量 + 本次限额不得超 host 容量；无限额默认放行 */
    public void admit(double runningCpus, long runningMemory, Limits requested) {
        if (requested.unlimited()) {
            return;
        }
        if (runningCpus + requested.cpu() > hostCpus) {
            throw new IllegalStateException("cpu 超限: 运行 " + runningCpus + " + 申请 " + requested.cpu() + " > 容量 " + hostCpus);
        }
        if (runningMemory + requested.memoryBytes() > hostMemoryBytes) {
            throw new IllegalStateException("memory 超限: 运行 " + runningMemory + " + 申请 " + requested.memoryBytes()
                    + " > 容量 " + hostMemoryBytes);
        }
    }
}
