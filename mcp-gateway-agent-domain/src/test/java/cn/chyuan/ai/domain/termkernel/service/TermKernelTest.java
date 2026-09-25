package cn.chyuan.ai.domain.termkernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端仿真内核测试（工单 0817-0824 CS1-CS8，alacritty 思想）。
 * 网格光标/CSI 光标序列/SGR 颜色属性/清屏清行滚动区域/宽字符 UTF-8/备用屏/回放快照一致性/SSE 帧联动。
 */
class TermKernelTest {

    private static final String ESC = "\u001b[";

    @Test
    void gridModelAndBounds() {
        Grid grid = new Grid(4, 8);
        assertEquals(4, grid.rows);
        grid.setCursor(2, 5);
        assertEquals(2, grid.curRow);
        assertEquals(5, grid.curCol);
        grid.setCursor(-3, 99);
        assertEquals(0, grid.curRow, "光标钳制");
        assertEquals(7, grid.curCol, "光标钳制列");
        assertThrows(IllegalArgumentException.class, () -> grid.cell(4, 0), "行越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> grid.cell(0, 8), "列越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> new Grid(0, 8), "非法行列拒绝");
    }

    @Test
    void printableWriteAndClamp() {
        TermEmulator term = new TermEmulator(3, 4);
        term.feed("ABCD");
        assertEquals(0, term.cursorRow());
        assertEquals(3, term.cursorCol(), "行末推进钳制");
        term.feed("E");
        assertEquals("ABCE", term.renderPlain(), "末列覆写不换行");
    }

    @Test
    void csiCursorMovesWithDefaults() {
        TermEmulator term = new TermEmulator(6, 10);
        term.feed(ESC + "3;4H");
        assertEquals(2, term.cursorRow());
        assertEquals(3, term.cursorCol());
        term.feed(ESC + "A");            // 缺省 1：上移
        assertEquals(1, term.cursorRow());
        term.feed(ESC + "2B");
        assertEquals(3, term.cursorRow());
        term.feed(ESC + "5C");
        assertEquals(8, term.cursorCol());
        term.feed(ESC + "99D");
        assertEquals(0, term.cursorCol(), "左移钳制");
        term.feed(ESC + "99A");
        assertEquals(0, term.cursorRow(), "上移钳制");
        term.feed(ESC + "5;5f");         // HPR 等价
        assertEquals(4, term.cursorRow());
    }

    @Test
    void sgrStylesAndColors() {
        TermEmulator term = new TermEmulator(3, 20);
        term.feed(ESC + "1;31mRED" + ESC + "0m" + "plain");
        Grid.Cell r = term.grid().cell(0, 0);
        assertTrue(r.style.bold());
        assertEquals("31", r.style.fg());
        Grid.Cell p = term.grid().cell(0, 3);
        assertTrue(p.style.isDefault(), "SGR 0 重置");
        term.feed(ESC + "38;5;196m" + "X");
        assertEquals("38;5;196", term.grid().cell(0, 8).style.fg(), "256 色");
        term.feed(ESC + "48;2;10;20;30m" + "Y");
        assertEquals("48;2;10;20;30", term.grid().cell(0, 9).style.bg(), "RGB 背景");
        term.feed(ESC + "3;4;7mZ");
        Grid.Cell z = term.grid().cell(0, 10);
        assertTrue(z.style.italic() && z.style.underline() && z.style.reverse());
    }

    @Test
    void unknownSequencesSkipped() {
        TermEmulator term = new TermEmulator(3, 10);
        term.feed(ESC + "99z" + ESC + "?25l" + "ok");
        assertEquals("ok", term.renderPlain(), "未知 CSI 私有序列跳过");
    }

    @Test
    void eraseDisplayAndLine() {
        TermEmulator term = new TermEmulator(3, 10);
        term.feed("AAAAAAAAAA\r\nBBBBBBBBBB\r\nCCCCCCCCCC");
        term.feed(ESC + "2;5H" + ESC + "0J");
        String plain = term.renderPlain();
        assertTrue(plain.startsWith("AAAA"), "ED0 光标上方保留");
        assertFalse(plain.contains("BBBBBBB"), "光标下方清空");
        TermEmulator line = new TermEmulator(2, 10);
        line.feed("0123456789\r\nABCDEFGHIJ");
        line.feed(ESC + "2;5H" + ESC + "1K");
        String l2 = line.renderPlain().split("\n")[1];
        assertEquals("     FGHIJ", l2, "EL1 清至行头");
    }

    @Test
    void scrollRegionAndScrollback() {
        TermEmulator term = new TermEmulator(3, 6);
        term.feed("L1\r\nL2\r\nL3\r\nL4");
        assertEquals(List.of("L1"), term.scrollback(), "底部换行上滚顶行入回看");
        assertEquals("L2\nL3\nL4", term.renderPlain());
        TermEmulator region = new TermEmulator(5, 6);
        region.feed(ESC + "2;4r");
        region.feed(ESC + "2;1H" + "A\r\nB\r\nC\r\nD\r\nE");
        region.feed(ESC + "4;1H" + "\n");
        String[] lines = region.renderPlain().split("\n", -1);
        assertEquals("", lines[0], "区域外顶部不受滚动影响");
        assertEquals("D", lines[1]);
        assertEquals("E", lines[2]);
        assertEquals(3, lines.length, "尾空行裁剪");
    }

    @Test
    void wideCharsPairCells() {
        TermEmulator term = new TermEmulator(3, 8);
        term.feed("中A文");
        Grid.Cell head = term.grid().cell(0, 0);
        Grid.Cell tail = term.grid().cell(0, 1);
        assertEquals('中', head.ch);
        assertTrue(tail.wideTail, "宽字符尾格");
        assertEquals('A', term.grid().cell(0, 2).ch);
        assertEquals("中A文", term.renderPlain());
    }

    @Test
    void wideTailCleanupOnOverwrite() {
        TermEmulator term = new TermEmulator(3, 8);
        term.feed("中文");
        term.feed(ESC + "1;1H" + "x");
        assertFalse(term.grid().cell(0, 1).wideTail, "覆写宽头清除其尾格");
        assertEquals(' ', term.grid().cell(0, 1).ch);
        assertTrue(term.grid().cell(0, 2).wideTail == false && term.grid().cell(0, 3).wideTail,
                "后续宽字符成对不受影响");
        assertEquals("x 文", term.renderPlain(), "尾格清空不压缩");
    }

    @Test
    void utf8BytesAndCombining() {
        TermEmulator term = new TermEmulator(3, 8);
        term.feedBytes("中文".getBytes(StandardCharsets.UTF_8));
        assertEquals("中文", term.renderPlain(), "UTF-8 多字节解码");
        byte[] broken = {(byte) 0xe4, (byte) 0xb8, 0x41};
        assertDoesNotThrow(() -> term.feedBytes(broken), "非法序列替换不抛出");
        TermEmulator combine = new TermEmulator(3, 8);
        combine.feed("e\u0301f");
        assertEquals("ef", combine.renderPlain(), "组合字符零格忽略");
    }

    @Test
    void altScreenSwitchAndRestore() {
        TermEmulator term = new TermEmulator(3, 10);
        term.feed("main");
        term.feed(ESC + "2;3H" + ESC + "7");
        term.feed(ESC + "?1049h");
        assertTrue(term.altMode(), "进入备用屏");
        term.feed("ALTSCREEN");
        assertFalse(term.renderPlain().contains("main"), "主屏内容隐藏");
        term.feed(ESC + "?1049l");
        assertFalse(term.altMode(), "退回主屏");
        assertEquals("main", term.renderPlain(), "主屏内容恢复");
        assertEquals(1, term.cursorRow(), "光标保存恢复");
        assertEquals(2, term.cursorCol());
    }

    @Test
    void scrollbackCapacityFifo() {
        TermEmulator term = new TermEmulator(3, 6);
        for (int i = 0; i < 130; i++) {
            term.feed("N" + i + "\r\n");
        }
        assertEquals(100, term.scrollback().size(), "回看缓冲上限");
        assertEquals("N28", term.scrollback().get(0), "最旧被挤出");
    }

    @Test
    void ansiRoundTripConsistency() {
        TermEmulator term = new TermEmulator(4, 20);
        term.feed("plain " + ESC + "1;31mred" + ESC + "0m " + ESC + "32mgreen" + ESC + "0m\r\n中文");
        String plain = term.renderPlain();
        String ansi = term.renderAnsi();
        assertTrue(ansi.contains("[1;31m"), "ANSI 重放含样式");
        assertTrue(ansi.contains("[0m"), "重置样式");
        TermEmulator replay = new TermEmulator(4, 20);
        replay.feed(ansi);
        assertEquals(plain, replay.renderPlain(), "重放快照文本一致");
    }

    @Test
    void portFeedFramesStreamLinkage() {
        TermPort port = TermPort.inMemory(4, 30);
        port.feedFrames(List.of("data: gateway ", "data: streaming", ": ping", "event: end"));
        assertTrue(port.renderPlain().startsWith("gateway streaming"), "SSE 帧 data 载荷拼接写入");
        assertFalse(port.renderPlain().contains("ping"), "非 data 行忽略");
        port.feed("status" + ESC + "K");
        assertFalse(port.renderPlain().endsWith("statusK"), "EL 生效");
        port.feedBytes("日志".getBytes(StandardCharsets.UTF_8));
        assertTrue(port.renderPlain().contains("日志"));
        assertNotNull(port.scrollback());
    }

    @Test
    void incrementalEscafeSplitFeed() {
        TermEmulator term = new TermEmulator(3, 10);
        term.feed("A" + ESC);
        term.feed("1;31mB");
        Grid.Cell b = term.grid().cell(0, 1);
        assertTrue(b.style.bold(), "残缺 ESC 序列跨次喂入续接");
        assertEquals("AB", term.renderPlain());
    }
}
