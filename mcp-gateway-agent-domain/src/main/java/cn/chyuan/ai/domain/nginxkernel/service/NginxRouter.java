package cn.chyuan.ai.domain.nginxkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 路由与转发（工单 0924-0927/0929 DE2-DE5·DE7，nginx 思想）。
 * location 匹配优先级（精确=＞正则~按序＞最长前缀）/rewrite 正则替换 last 重匹配/upstream 加权轮询/代理路径拼接/访问日志占位符。
 */
public final class NginxRouter {

    public static final int HTTP_404 = 404;
    public static final int HTTP_503 = 503;
    public static final int HTTP_200 = 200;

    private final NginxConfig config;

    public NginxRouter(NginxConfig config) {
        this.config = config;
    }

    /** location 匹配：精确 = ＞ 正则 ~（按配置顺序）＞ 最长前缀；未命中 null（404） */
    public NginxConfig.Location match(String path) {
        List<NginxConfig.ServerBlock> servers = config.servers;
        for (NginxConfig.ServerBlock server : servers) {
            for (NginxConfig.Location location : server.locations) {
                if (location.modifier.equals("=") && location.pattern.equals(path)) {
                    return location;
                }
            }
        }
        for (NginxConfig.ServerBlock server : servers) {
            for (NginxConfig.Location location : server.locations) {
                if (location.modifier.equals("~") && java.util.regex.Pattern.compile(location.pattern)
                        .matcher(path).find()) {
                    return location;
                }
            }
        }
        NginxConfig.Location best = null;
        for (NginxConfig.ServerBlock server : servers) {
            for (NginxConfig.Location location : server.locations) {
                if (location.modifier.isEmpty() && path.startsWith(location.pattern)) {
                    if (best == null || location.pattern.length() > best.pattern.length()) {
                        best = location;
                    }
                }
            }
        }
        return best;
    }

    /** rewrite：取 location 的 rewrite 指令（regex replacement [last]），对路径替换；last 触发一次重匹配 */
    public String rewrite(String path, NginxConfig.Location location) {
        String directive = location.directives.get("rewrite");
        if (directive == null) {
            return path;
        }
        String[] parts = directive.split(" ");
        String newPath = path.replaceAll(parts[0], parts[1]);
        return newPath;
    }

    /** 完整路由：先 rewrite（若 location 有 rewrite 则重匹配一次），返回最终 location */
    public NginxConfig.Location route(String path) {
        NginxConfig.Location first = match(path);
        if (first == null) {
            return null;
        }
        if (first.directives.containsKey("rewrite")) {
            String rewritten = rewrite(path, first);
            NginxConfig.Location second = match(rewritten);
            return second != null ? second : first;
        }
        return first;
    }

    /** upstream：加权轮询（按权重展开序列循环取），全组无服务器拒绝 */
    public String pickUpstream(String upstreamName, int requestSeq) {
        NginxConfig.Upstream upstream = config.upstreams.get(upstreamName);
        if (upstream == null || upstream.servers.isEmpty()) {
            throw new IllegalArgumentException("未知或空 upstream: " + upstreamName);
        }
        List<String> expanded = new ArrayList<>();
        for (NginxConfig.UpstreamServer s : upstream.servers) {
            for (int i = 0; i < s.weight(); i++) {
                expanded.add(s.address());
            }
        }
        return expanded.get(Math.floorMod(requestSeq, expanded.size()));
    }

    /** 代理转发：proxy_pass http://up[/base]，带 base 时剥离 location 前缀拼接 base */
    public Forward forward(String path, NginxConfig.Location location, int requestSeq) {
        String proxyPass = location.directives.get("proxy_pass");
        if (proxyPass == null) {
            throw new IllegalArgumentException("location 无 proxy_pass");
        }
        String upstreamName = proxyPass.replace("http://", "");
        String base = "";
        int slash = upstreamName.indexOf('/');
        if (slash >= 0) {
            base = upstreamName.substring(slash);
            upstreamName = upstreamName.substring(0, slash);
        }
        String server = pickUpstream(upstreamName, requestSeq);
        String effective = path;
        if (!base.isEmpty()) {
            effective = base + path.substring(location.pattern.length());
        }
        return new Forward("http://" + server + effective, server);
    }

    /** 转发结果 */
    public record Forward(String url, String upstreamServer) {
    }

    /** 访问日志：$remote_addr/$status/$request_time/$upstream_addr 占位符替换 */
    public static String formatLog(String format, Map<String, String> variables) {
        String out = format;
        TreeMap<String, String> sorted = new TreeMap<>(variables);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            out = out.replace("$" + e.getKey(), e.getValue());
        }
        return out;
    }
}
