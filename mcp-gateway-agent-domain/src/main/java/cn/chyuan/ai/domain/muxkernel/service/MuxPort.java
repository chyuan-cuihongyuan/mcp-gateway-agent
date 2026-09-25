package cn.chyuan.ai.domain.muxkernel.service;

import java.util.List;

/**
 * 会话复用端口（工单 0853 CV8，tmux 思想）。
 * newSession·split·select·closePane 入口统一编排/与 termkernel 纯文本快照作窗格内容形态只读联动（泛型不 import）/
 * mux-kernel.enabled 默认关（开启才改变行为）。
 */
public interface MuxPort {

    void newSession(String name);

    void split(String session, String direction);

    void selectPane(String session, int paneId);

    void closePane(String session, int paneId);

    /** 窗格内容写入 */
    void setPaneContent(String session, int paneId, String content);

    String paneContent(String session, int paneId);

    /** termkernel 只读联动形态：终端纯文本快照直接作为活动窗格内容（形状数据不 import termkernel） */
    void adoptSnapshot(String session, String plainSnapshot);

    int paneCount(String session);

    static MuxPort inMemory(int width, int height) {
        return new InMemoryMux(width, height);
    }
}

final class InMemoryMux implements MuxPort {

    private final Mux mux;

    InMemoryMux(int width, int height) {
        this.mux = new Mux(width, height);
        mux.newSession("main");
    }

    @Override
    public void newSession(String name) {
        mux.newSession(name);
    }

    @Override
    public void split(String session, String direction) {
        mux.split(session, direction);
    }

    @Override
    public void selectPane(String session, int paneId) {
        mux.selectPane(session, paneId);
    }

    @Override
    public void closePane(String session, int paneId) {
        mux.closePane(session, paneId);
    }

    @Override
    public void setPaneContent(String session, int paneId, String content) {
        mux.session(session).activeWindow().pane(paneId).content = content;
    }

    @Override
    public String paneContent(String session, int paneId) {
        return mux.session(session).activeWindow().pane(paneId).content;
    }

    @Override
    public void adoptSnapshot(String session, String plainSnapshot) {
        Mux.Window w = mux.session(session).activeWindow();
        w.activePane().content = plainSnapshot;
    }

    @Override
    public int paneCount(String session) {
        return mux.session(session).activeWindow().panes.size();
    }

    Mux mux() {
        return mux;
    }

    static List<String> directions() {
        return List.of("horizontal", "vertical");
    }
}
