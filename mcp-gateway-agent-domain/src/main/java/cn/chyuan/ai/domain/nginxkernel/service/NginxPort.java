package cn.chyuan.ai.domain.nginxkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 路由配置端口（工单 0930 DE8，nginx 思想）。
 * load·route·forward 入口统一编排/与 streamkernel 帧文本作日志与响应形态只读联动（泛型文本不 import）/
 * nginx-kernel.enabled 默认关（开启才改变行为）。
 */
public interface NginxPort {

    NginxRouter load(String configText);

    NginxConfig.Location route(String path);

    NginxRouter.Forward forward(String path, int requestSeq);

    /** streamkernel 只读联动形态：帧文本直接作为访问日志行（形状数据不 import streamkernel） */
    String accessLog(String format, Map<String, String> variables);

    static NginxPort inMemory(String configText) {
        return new InMemoryNginx(configText);
    }
}

final class InMemoryNginx implements NginxPort {

    private final NginxRouter router;

    InMemoryNginx(String configText) {
        this.router = new NginxRouter(NginxConfig.parse(configText));
    }

    @Override
    public NginxRouter load(String configText) {
        return new NginxRouter(NginxConfig.parse(configText));
    }

    @Override
    public NginxConfig.Location route(String path) {
        return router.route(path);
    }

    @Override
    public NginxRouter.Forward forward(String path, int requestSeq) {
        NginxConfig.Location location = router.route(path);
        if (location == null) {
            throw new IllegalArgumentException("无匹配 location: " + path);
        }
        return router.forward(path, location, requestSeq);
    }

    @Override
    public String accessLog(String format, Map<String, String> variables) {
        return NginxRouter.formatLog(format, variables);
    }
}
