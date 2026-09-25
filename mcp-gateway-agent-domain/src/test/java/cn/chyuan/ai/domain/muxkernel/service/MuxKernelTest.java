package cn.chyuan.ai.domain.muxkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话复用内核测试（工单 0846-0853 CV1-CV8，tmux 思想）。
 * 会话窗格树/索引与循环切换/分屏几何最小尺寸/even 布局/关闭回收/前缀键冲突/粘贴缓冲/分离附着/端口快照联动。
 */
class MuxKernelTest {

    @Test
    void sessionTreeAndIndex() {
        Mux mux = new Mux(20, 10);
        Mux.Session s = mux.newSession("dev");
        assertThrows(IllegalArgumentException.class, () -> mux.newSession("dev"), "重名会话拒绝");
        assertEquals(1, s.windows.size(), "首窗口自动建");
        assertEquals(0, s.activeWindow);
        Mux.Window w2 = mux.newWindow("dev", "logs");
        assertEquals(1, w2.index, "窗口索引自动递增");
        assertEquals(1, s.activeWindow);
        assertThrows(IllegalArgumentException.class, () -> mux.session("nope"), "未知会话拒绝");
    }

    @Test
    void windowSwitching() {
        Mux mux = new Mux(20, 10);
        mux.newSession("dev");
        mux.newWindow("dev", "logs");
        mux.newWindow("dev", "metrics");
        mux.selectWindow("dev", 0);
        assertEquals(0, mux.session("dev").activeWindow);
        mux.nextWindow("dev");
        assertEquals(1, mux.session("dev").activeWindow);
        mux.nextWindow("dev");
        mux.nextWindow("dev");
        assertEquals(0, mux.session("dev").activeWindow, "循环切换");
        assertThrows(IllegalArgumentException.class, () -> mux.selectWindow("dev", 9), "未知索引拒绝");
    }

    @Test
    void splitGeometryAndMinSize() {
        Mux mux = new Mux(20, 10);
        mux.newSession("dev");
        Mux.Pane right = mux.split("dev", "horizontal");
        Mux.Window w = mux.session("dev").activeWindow();
        assertEquals(new Mux.Rect(0, 0, 10, 10), w.pane(0).rect, "左半");
        assertEquals(new Mux.Rect(10, 0, 10, 10), right.rect, "右半");
        Mux.Pane bottom = mux.split("dev", "vertical");
        assertEquals(new Mux.Rect(10, 5, 10, 5), bottom.rect, "右半上下切取下半");
        Mux narrow = new Mux(3, 10);
        narrow.newSession("s");
        assertThrows(IllegalArgumentException.class, () -> narrow.split("s", "horizontal"), "宽度不足拒绝");
        Mux shortMux = new Mux(20, 1);
        shortMux.newSession("s");
        assertThrows(IllegalArgumentException.class, () -> shortMux.split("s", "vertical"), "高度不足拒绝");
        assertThrows(IllegalArgumentException.class, () -> mux.split("dev", "diagonal"), "未知方向拒绝");
    }

    @Test
    void layoutEvenAndCloseMerge() {
        Mux mux = new Mux(20, 10);
        mux.newSession("dev");
        mux.split("dev", "horizontal");
        mux.split("dev", "horizontal");
        mux.layoutEven("dev");
        Mux.Window w = mux.session("dev").activeWindow();
        assertEquals(3, w.panes.size());
        assertEquals(new Mux.Rect(0, 0, 6, 10), w.panes.get(0).rect, "even 均分第一列");
        int totalWidth = w.panes.stream().mapToInt(p -> p.rect.w()).sum();
        assertEquals(20, totalWidth, "布局覆盖全宽");
        mux.closePane("dev", 0);
        assertEquals(2, w.panes.size(), "关闭回收");
        totalWidth = w.panes.stream().mapToInt(p -> p.rect.w()).sum();
        assertEquals(20, totalWidth, "重排后仍覆盖全宽");
        mux.closePane("dev", 1);
        mux.closePane("dev", 2);
        assertTrue(mux.listSessions().isEmpty(), "窗格清空级联关窗后会话销毁");
    }

    @Test
    void prefixKeyBindings() {
        KeyTable keys = new KeyTable();
        assertEquals("C-b", keys.prefix());
        keys.bind("p", "paste");
        keys.bind("%", "split-horizontal");
        assertEquals("paste", keys.lookup("p"));
        assertThrows(IllegalArgumentException.class, () -> keys.bind("p", "other"), "chord 冲突拒绝");
        keys.bind("p", "paste");
        assertEquals(2, keys.size());
        keys.unbind("p");
        assertNull(keys.lookup("p"));
        assertEquals("none", keys.press(false, "%"), "未按前缀忽略");
        assertEquals("split-horizontal", keys.press(true, "%"));
        assertEquals("unknown", keys.press(true, "zzz"), "未绑定 chord");
        keys.setPrefix("C-a");
        assertEquals("C-a", keys.prefix());
    }

    @Test
    void pasteBufferStackAndNamed() {
        KeyTable.PasteBuffer buf = new KeyTable.PasteBuffer(3);
        buf.push("one");
        buf.push("two");
        buf.push("three");
        assertEquals(3, buf.size());
        buf.push("four");
        assertEquals(3, buf.size(), "上限淘汰");
        assertEquals("four", buf.pop(), "LIFO");
        assertEquals("three", buf.pop());
        buf.put("main", "snapshot-1");
        assertEquals("snapshot-1", buf.get("main"));
        assertThrows(IllegalArgumentException.class, () -> buf.get("nope"), "未知具名缓冲拒绝");
        KeyTable.PasteBuffer empty = new KeyTable.PasteBuffer(1);
        assertThrows(IllegalStateException.class, empty::pop, "空缓冲弹出拒绝");
        assertThrows(IllegalArgumentException.class, () -> new KeyTable.PasteBuffer(0), "非法上限拒绝");
    }

    @Test
    void attachDetachAndKill() {
        Mux mux = new Mux(20, 10);
        mux.newSession("dev");
        mux.attach("dev");
        mux.attach("dev");
        assertEquals(2, mux.session("dev").clients, "多客户端附着");
        mux.detach("dev");
        assertEquals(1, mux.session("dev").clients);
        mux.detach("dev");
        mux.detach("dev");
        assertEquals(0, mux.session("dev").clients, "分离下限为 0 且会话保留");
        assertEquals(1, mux.listSessions().size());
        mux.killSession("dev");
        assertEquals(0, mux.listSessions().size());
        assertThrows(IllegalArgumentException.class, () -> mux.attach("dev"), "销毁后附着拒绝");
    }

    @Test
    void paneSelectionIndependent() {
        Mux mux = new Mux(20, 10);
        mux.newSession("dev");
        mux.newWindow("dev", "second");
        mux.split("dev", "horizontal");
        mux.selectPane("dev", 0);
        Mux.Window second = mux.session("dev").activeWindow();
        assertEquals(0, second.activePane, "窗口内窗格选择独立");
        mux.selectWindow("dev", 0);
        assertEquals(1, mux.session("dev").activeWindow().panes.size(), "首窗口不受第二窗分屏影响");
    }

    @Test
    void portOrchestrationAndSnapshotLinkage() {
        MuxPort port = MuxPort.inMemory(20, 10);
        assertEquals(1, port.paneCount("main"));
        port.split("main", "horizontal");
        port.split("main", "horizontal");
        assertEquals(3, port.paneCount("main"));
        port.selectPane("main", 1);
        port.setPaneContent("main", 1, "logs");
        assertEquals("logs", port.paneContent("main", 1));
        port.adoptSnapshot("main", "gateway metrics\nmemory ok");
        assertTrue(port.paneContent("main", 1).startsWith("gateway metrics"), "终端快照作窗格内容只读联动");
        port.closePane("main", 0);
        assertEquals(2, port.paneCount("main"));
        port.newSession("extra");
        assertThrows(IllegalArgumentException.class, () -> port.split("ghost", "horizontal"), "未知会话拒绝");
    }
}
