package cn.chyuan.ai.domain.socketkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 房间与广播（工单 0962 ED3 / 0963 ED4，socket.io 思想）。
 * join/leave/成员查询/重复 join 幂等；房间广播排除发送者；全员广播；跨 ns 房间不可见。
 */
public final class Rooms {

    private final Map<String, Map<String, Set<String>>> byNamespace = new HashMap<>();

    /** join：重复幂等（房间按插入序，保证广播目标确定性） */
    public void join(String ns, String sid, String room) {
        byNamespace.computeIfAbsent(ns, k -> new LinkedHashMap<>())
                .computeIfAbsent(room, k -> new LinkedHashSet<>())
                .add(sid);
    }


    /** leave：未在房间返回 false */
    public boolean leave(String ns, String sid, String room) {
        Map<String, Set<String>> rooms = byNamespace.get(ns);
        if (rooms == null) {
            return false;
        }
        Set<String> members = rooms.get(room);
        return members != null && members.remove(sid);
    }

    /** 连接断开：退出该 ns 全部房间 */
    public void leaveAll(String ns, String sid) {
        Map<String, Set<String>> rooms = byNamespace.get(ns);
        if (rooms != null) {
            rooms.values().forEach(members -> members.remove(sid));
        }
    }

    /** 房间成员（插入序） */
    public List<String> members(String ns, String room) {
        Map<String, Set<String>> rooms = byNamespace.get(ns);
        if (rooms == null || rooms.get(room) == null) {
            return List.of();
        }
        return List.copyOf(rooms.get(room));
    }

    /** 广播目标：room 为 null 表示 ns 全员；排除发送者；跨 ns 房间不可见 */
    public List<String> targets(String ns, String room, String exceptSid) {
        Set<String> result = new LinkedHashSet<>();
        Map<String, Set<String>> rooms = byNamespace.get(ns);
        if (rooms == null) {
            return List.of();
        }
        if (room == null) {
            rooms.values().forEach(result::addAll);
        } else {
            result.addAll(rooms.getOrDefault(room, Set.of()));
        }
        if (exceptSid != null) {
            result.remove(exceptSid);
        }
        return new ArrayList<>(result);
    }
}
