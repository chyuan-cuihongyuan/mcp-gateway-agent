package cn.chyuan.ai.domain.muxkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话窗格树（工单 0846-0849/0852 CV1-CV4·CV7，tmux 会话树思想）。
 * server·session·window·pane 层级/唯一索引/索引与循环切换/分屏几何与最小尺寸/even 布局/关闭回收/分离附着。
 */
public final class Mux {

    /** 窗格几何区域 */
    public record Rect(int x, int y, int w, int h) {
    }

    public static final class Pane {
        public final int id;
        public Rect rect;
        public String content = "";
        public boolean active;

        Pane(int id, Rect rect) {
            this.id = id;
            this.rect = rect;
        }
    }

    public static final class Window {
        public final int index;
        public final String name;
        public final List<Pane> panes = new ArrayList<>();
        public int activePane = -1;
        private int nextPaneId = 0;

        Window(int index, String name, int w, int h) {
            this.index = index;
            this.name = name;
            panes.add(new Pane(nextPaneId++, new Rect(0, 0, w, h)));
            activePane = 0;
            panes.get(0).active = true;
        }

        public Pane activePane() {
            return panes.get(activePane);
        }

        public Pane pane(int id) {
            for (Pane p : panes) {
                if (p.id == id) {
                    return p;
                }
            }
            throw new IllegalArgumentException("未知窗格: " + id);
        }
    }

    public static final class Session {
        public final String name;
        public final List<Window> windows = new ArrayList<>();
        public int activeWindow = 0;
        public int clients = 0;
        public boolean alive = true;

        Session(String name, int w, int h) {
            this.name = name;
            windows.add(new Window(0, "main", w, h));
        }

        public Window activeWindow() {
            return windows.get(activeWindow);
        }
    }

    public static final int MIN_W = 2;
    public static final int MIN_H = 1;

    private final int width;
    private final int height;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public Mux(int width, int height) {
        if (width < MIN_W || height < MIN_H) {
            throw new IllegalArgumentException("服务器尺寸过小");
        }
        this.width = width;
        this.height = height;
    }

    /** 新会话：重名拒绝 */
    public Session newSession(String name) {
        if (sessions.containsKey(name)) {
            throw new IllegalArgumentException("会话已存在: " + name);
        }
        Session s = new Session(name, width, height);
        sessions.put(name, s);
        return s;
    }

    public Session session(String name) {
        Session s = sessions.get(name);
        if (s == null || !s.alive) {
            throw new IllegalArgumentException("未知会话: " + name);
        }
        return s;
    }

    public List<Session> listSessions() {
        return List.copyOf(sessions.values());
    }

    /** 附着/分离：分离保留会话 */
    public void attach(String name) {
        session(name).clients++;
    }

    public void detach(String name) {
        Session s = session(name);
        if (s.clients > 0) {
            s.clients--;
        }
    }

    /** 销毁会话 */
    public void killSession(String name) {
        session(name).alive = false;
        sessions.remove(name);
    }

    /** 新窗口：索引自动递增 */
    public Window newWindow(String sessionName, String windowName) {
        Session s = session(sessionName);
        Window w = new Window(s.windows.size(), windowName, width, height);
        s.windows.add(w);
        s.activeWindow = w.index;
        return w;
    }

    /** 选择窗口：未知索引拒绝 */
    public void selectWindow(String sessionName, int index) {
        Session s = session(sessionName);
        if (index < 0 || index >= s.windows.size()) {
            throw new IllegalArgumentException("未知窗口索引: " + index);
        }
        s.activeWindow = index;
    }

    /** 循环切换 */
    public void nextWindow(String sessionName) {
        Session s = session(sessionName);
        s.activeWindow = (s.activeWindow + 1) % s.windows.size();
    }

    /** 选择活动窗格 */
    public void selectPane(String sessionName, int paneId) {
        Window w = session(sessionName).activeWindow();
        Pane p = w.pane(paneId);
        for (Pane other : w.panes) {
            other.active = false;
        }
        p.active = true;
        w.activePane = w.panes.indexOf(p);
    }

    /** 分屏：horizontal 左右切，vertical 上下切；小于最小尺寸拒绝 */
    public Pane split(String sessionName, String direction) {
        Window w = session(sessionName).activeWindow();
        Pane parent = w.activePane();
        Rect r = parent.rect;
        Pane fresh;
        if (direction.equals("horizontal")) {
            if (r.w() < MIN_W * 2) {
                throw new IllegalArgumentException("宽度不足分屏: " + r.w());
            }
            int leftW = r.w() / 2;
            parent.rect = new Rect(r.x(), r.y(), leftW, r.h());
            fresh = new Pane(w.nextPaneId++, new Rect(r.x() + leftW, r.y(), r.w() - leftW, r.h()));
        } else if (direction.equals("vertical")) {
            if (r.h() < MIN_H * 2) {
                throw new IllegalArgumentException("高度不足分屏: " + r.h());
            }
            int topH = r.h() / 2;
            parent.rect = new Rect(r.x(), r.y(), r.w(), topH);
            fresh = new Pane(w.nextPaneId++, new Rect(r.x(), r.y() + topH, r.w(), r.h() - topH));
        } else {
            throw new IllegalArgumentException("未知分屏方向: " + direction);
        }
        parent.active = false;
        fresh.active = true;
        w.activePane = w.panes.size();
        w.panes.add(fresh);
        return fresh;
    }

    /** even 布局：按窗格数均分宽度（超过最小宽度约束则回退单列行切） */
    public void layoutEven(String sessionName) {
        Window w = session(sessionName).activeWindow();
        int n = w.panes.size();
        if (n == 0) {
            return;
        }
        int cols = Math.min(n, width / MIN_W);
        int colWidth = width / cols;
        int rows = (int) Math.ceil(n / (double) cols);
        int rowHeight = Math.max(MIN_H, height / Math.max(1, rows));
        int x = 0;
        int placed = 0;
        for (int c = 0; c < cols; c++) {
            int w0 = c == cols - 1 ? width - x : colWidth;
            for (int rr = 0; rr < rows && placed < n; rr++) {
                int y0 = rr * rowHeight;
                int h0 = rr == rows - 1 ? height - y0 : rowHeight;
                w.panes.get(placed).rect = new Rect(x, y0, w0, h0);
                placed++;
            }
            x += w0;
        }
    }

    /** 关闭窗格：剩余窗格触发 even 重排（邻接合并简化口径） */
    public void closePane(String sessionName, int paneId) {
        Window w = session(sessionName).activeWindow();
        Pane p = w.pane(paneId);
        w.panes.remove(p);
        if (w.panes.isEmpty()) {
            killWindow(sessionName, w);
            return;
        }
        if (w.activePane >= w.panes.size()) {
            w.activePane = w.panes.size() - 1;
        }
        w.panes.get(w.activePane).active = true;
        layoutEven(sessionName);
    }

    private void killWindow(String sessionName, Window w) {
        Session s = session(sessionName);
        s.windows.remove(w);
        if (s.windows.isEmpty()) {
            killSession(sessionName);
            return;
        }
        if (s.activeWindow >= s.windows.size()) {
            s.activeWindow = s.windows.size() - 1;
        }
    }
}
