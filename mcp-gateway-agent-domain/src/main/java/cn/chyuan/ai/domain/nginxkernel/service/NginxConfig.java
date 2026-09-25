package cn.chyuan.ai.domain.nginxkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * nginx 配置解析（工单 0923 DE1，nginx 思想）。
 * upstream 与 server/location 块解析/指令行/缺 server 块拒绝。
 */
public final class NginxConfig {

    /** upstream 服务器：地址与权重 */
    public record UpstreamServer(String address, int weight) {
    }

    /** upstream 组 */
    public static final class Upstream {
        public final String name;
        public final List<UpstreamServer> servers = new ArrayList<>();

        Upstream(String name) {
            this.name = name;
        }
    }

    /** location：修饰符（""=前缀、"="精确、"~"正则）+ 模式 + 指令 */
    public static final class Location {
        public final String modifier;
        public final String pattern;
        public final Map<String, String> directives = new LinkedHashMap<>();

        Location(String modifier, String pattern) {
            this.modifier = modifier;
            this.pattern = pattern;
        }
    }

    /** server 块 */
    public static final class ServerBlock {
        public int listenPort = 80;
        public final List<Location> locations = new ArrayList<>();
        public final Map<String, String> directives = new LinkedHashMap<>();
    }

    public final Map<String, Upstream> upstreams = new LinkedHashMap<>();
    public final List<ServerBlock> servers = new ArrayList<>();

    /** 解析配置文本（行式：指令以 ; 结尾，块以 { } 包裹）；无 server 块拒绝 */
    public static NginxConfig parse(String text) {
        NginxConfig config = new NginxConfig();
        List<String> tokens = tokenize(text);
        int[] at = {0};
        while (at[0] < tokens.size()) {
            String token = tokens.get(at[0]);
            if (token.equals("upstream")) {
                String name = tokens.get(at[0] + 1);
                at[0] += 2;
                parseUpstream(config, name, tokens, at);
            } else if (token.equals("server") && at[0] + 1 < tokens.size() && tokens.get(at[0] + 1).equals("{")) {
                at[0] += 2;
                parseServer(config, tokens, at);
            } else {
                at[0]++;
            }
        }
        if (config.servers.isEmpty()) {
            throw new IllegalArgumentException("缺少 server 块");
        }
        return config;
    }

    private static void parseUpstream(NginxConfig config, String name, List<String> tokens, int[] at) {
        Upstream upstream = new Upstream(name);
        config.upstreams.put(name, upstream);
        expect(tokens, at, "{");
        while (!tokens.get(at[0]).equals("}")) {
            String token = tokens.get(at[0]);
            if (token.equals("server")) {
                at[0]++;
                String address = tokens.get(at[0]);
                at[0]++;
                int weight = 1;
                while (!tokens.get(at[0]).equals(";")) {
                    if (tokens.get(at[0]).startsWith("weight=")) {
                        weight = Integer.parseInt(tokens.get(at[0]).substring(7));
                    }
                    at[0]++;
                }
                at[0]++;
                upstream.servers.add(new UpstreamServer(address, weight));
            } else {
                at[0]++;
            }
        }
        at[0]++;
    }

    private static void parseServer(NginxConfig config, List<String> tokens, int[] at) {
        ServerBlock server = new ServerBlock();
        config.servers.add(server);
        while (!tokens.get(at[0]).equals("}")) {
            String token = tokens.get(at[0]);
            if (token.equals("listen")) {
                server.listenPort = Integer.parseInt(tokens.get(at[0] + 1));
                at[0] += 2;
                expect(tokens, at, ";");
            } else if (token.equals("location")) {
                at[0]++;
                String modifier = "";
                String pattern;
                if (tokens.get(at[0]).equals("=") || tokens.get(at[0]).equals("~")) {
                    modifier = tokens.get(at[0]);
                    at[0]++;
                }
                pattern = tokens.get(at[0]);
                at[0]++;
                parseLocation(server, modifier, pattern, tokens, at);
            } else if (!tokens.get(at[0]).equals("}")) {
                String key = token;
                at[0]++;
                StringBuilder value = new StringBuilder();
                while (!tokens.get(at[0]).equals(";")) {
                    if (value.length() > 0) {
                        value.append(' ');
                    }
                    value.append(tokens.get(at[0]));
                    at[0]++;
                }
                at[0]++;
                server.directives.put(key, value.toString());
            } else {
                at[0]++;
            }
        }
        at[0]++;
    }

    private static void parseLocation(ServerBlock server, String modifier, String pattern,
                                       List<String> tokens, int[] at) {
        Location location = new Location(modifier, pattern);
        server.locations.add(location);
        expect(tokens, at, "{");
        while (!tokens.get(at[0]).equals("}")) {
            String key = tokens.get(at[0]);
            at[0]++;
            StringBuilder value = new StringBuilder();
            while (!tokens.get(at[0]).equals(";") && !tokens.get(at[0]).equals("}")) {
                if (value.length() > 0) {
                    value.append(' ');
                }
                value.append(tokens.get(at[0]));
                at[0]++;
            }
            if (!value.isEmpty()) {
                location.directives.put(key, value.toString());
            }
            if (tokens.get(at[0]).equals(";")) {
                at[0]++;
            }
        }
        at[0]++;
    }

    private static void expect(List<String> tokens, int[] at, String token) {
        if (at[0] >= tokens.size() || !tokens.get(at[0]).equals(token)) {
            throw new IllegalArgumentException("期望 " + token + " 实得 "
                    + (at[0] < tokens.size() ? tokens.get(at[0]) : "EOF"));
        }
        at[0]++;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{' || c == '}' || c == ';') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
        }
        return tokens;
    }
}
