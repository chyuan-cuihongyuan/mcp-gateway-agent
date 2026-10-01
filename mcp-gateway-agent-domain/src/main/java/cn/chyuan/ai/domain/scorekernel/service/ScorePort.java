package cn.chyuan.ai.domain.scorekernel.service;

import java.util.List;

/**
 * 调度评分端口（工单 1195 FD8，kubernetes 思想）。
 * register·filter·score·bind 入口统一编排：节点注册·谓词过滤·亲和性·LeastRequested 评分·
 * 抢占·绑定·多 pod 队列组合管线/schedkernel 可运行队列形状只读联动
 * （g/p/runq 语义名对齐，不 import schedkernel）/score-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ScorePort {

    /** 注册节点容量（FD1） */
    void node(String name, int cpu, int mem);

    /** 节点标签（FD3） */
    void label(String node, String key, String value);

    /** 节点污点（FD2） */
    void taint(String node, String key);

    /** 提交 pod 入调度队列（FD7） */
    void submit(String pod, int priority, int cpu, int mem);

    /** 节点选择器：键值全匹配（FD2） */
    void selector(String pod, String key, String value);

    /** 污点容忍（FD2） */
    void tolerate(String pod, String taint);

    /** 节点亲和：命中加分（FD3） */
    void affinity(String pod, String key, String value);

    /** 反亲和：节点持有该标签键即滤除（FD3） */
    void antiAffinity(String pod, String labelKey);

    /** 可调度节点（FD2）：全滤除得空集 */
    List<String> feasible(String pod);

    /** LeastRequested 评分 0-100（FD4） */
    int score(String node, String pod);

    /** 绑定：过滤→评分→最高分落位；无可调度集走抢占；无受害可选拒绝（FD5/FD6） */
    String bind(String pod);

    /** 队列调度：按优先级出队（同优先级 FIFO），失败者留队不阻塞后继，返回本轮绑定（FD7） */
    List<String> schedule();

    /** 队列余量（FD7） */
    int queued();

    /** pod 绑定节点（FD6） */
    String boundTo(String pod);

    /** schedkernel 可运行队列形状只读联动（g/p/runq） */
    List<String> queueShape();

    static ScorePort inMemory() {
        return new ScoreServer();
    }
}
