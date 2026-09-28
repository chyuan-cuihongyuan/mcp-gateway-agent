package cn.chyuan.ai.domain.casbinkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略授权端口（工单 0959 EC8，casbin 思想）。
 * enforce·add·remove 入口统一编排/与 permkernel 许可串主体作字符串形态只读联动（泛型文本不 import）/
 * casbin-kernel.enabled 默认关（开启才改变行为）。
 */
public interface CasbinPort {

    /** 加载模型（重载模型并重建策略） */
    void loadModel(String modelText);

    /** 覆盖式加载策略行 */
    void loadPolicy(List<String> lines);

    /** enforce：请求裁定 */
    boolean enforce(List<String> request);

    /** 增量加策略：重复拒绝并通知 */
    void addPolicy(String line);

    /** 增量移除：不存在拒绝并通知 */
    void removePolicy(String line);

    /** 变更通知观察者 */
    interface Watcher {
        void update(String event);
    }

    void watch(Watcher watcher);

    /** permkernel 许可串主体形态只读联动：主体+动作 → 许可串（形状数据不 import permkernel） */
    static String permitOf(String subject, String action) {
        return "perm://" + subject + "/" + action;
    }

    static CasbinPort inMemory() {
        return new InMemoryCasbin();
    }
}

final class InMemoryCasbin implements CasbinPort {

    private String modelText;
    private CasbinEngine engine;
    private final List<String> lines = new ArrayList<>();
    private final List<CasbinPort.Watcher> watchers = new ArrayList<>();

    @Override
    public void loadModel(String modelText) {
        this.modelText = modelText;
        rebuild();
    }

    @Override
    public void loadPolicy(List<String> loaded) {
        requireEngine();
        lines.clear();
        lines.addAll(loaded);
        rebuild();
    }

    @Override
    public boolean enforce(List<String> request) {
        requireEngine();
        return engine.enforce(request);
    }

    @Override
    public void addPolicy(String line) {
        requireEngine();
        if (lines.contains(line)) {
            throw new IllegalArgumentException("重复策略: " + line);
        }
        lines.add(line);
        rebuild();
        notify("add:" + line);
    }

    @Override
    public void removePolicy(String line) {
        requireEngine();
        if (!lines.remove(line)) {
            throw new IllegalArgumentException("不存在策略: " + line);
        }
        rebuild();
        notify("remove:" + line);
    }

    @Override
    public void watch(CasbinPort.Watcher watcher) {
        watchers.add(watcher);
    }

    private void rebuild() {
        CasbinEngine fresh = new CasbinEngine(CasbinModel.parse(modelText));
        for (String line : lines) {
            fresh.loadPolicy(line);
        }
        engine = fresh;
    }

    private void notify(String event) {
        for (CasbinPort.Watcher watcher : watchers) {
            watcher.update(event);
        }
    }

    private void requireEngine() {
        if (engine == null) {
            throw new IllegalStateException("模型未加载");
        }
    }
}
