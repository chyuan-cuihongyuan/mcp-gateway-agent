package cn.chyuan.ai.domain.termkernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 终端仿真器（工单 0818 CS2/0819 CS3/0820 CS4/0822 CS6/0823 CS7，alacritty 终端思想）。
 * CSI 光标序列解析（缺省参数/非法序列跳过）/SGR 颜色属性/清屏清行与滚动区域/备用屏切换保存恢复/回看缓冲与快照渲染。
 */
public final class TermEmulator {

    private static final String ESC = "\u001b[";
    private static final int SCROLLBACK_CAP = 100;

    private final Grid main;
    private final Grid alt;
    private Grid active;
    private boolean altMode = false;
    private final Style[] currentStyle = {Style.DEFAULT};
    private int[] savedMainCursor = {0, 0};
    private int[] savedAltCursor = {0, 0};
    private final Deque<String> scrollback = new ArrayDeque<>();
    private final StringBuilder pending = new StringBuilder();

    public TermEmulator(int rows, int cols) {
        this.main = new Grid(rows, cols);
        this.alt = new Grid(rows, cols);
        this.active = main;
        main.onScrollUp = () -> pushScrollback(main);
    }

    public boolean altMode() {
        return altMode;
    }

    public Grid grid() {
        return active;
    }

    public List<String> scrollback() {
        return List.copyOf(scrollback);
    }

    public int cursorRow() {
        return active.curRow;
    }

    public int cursorCol() {
        return active.curCol;
    }

    /** UTF-8 字节流解码（非法序列替换不抛出）后喂入 */
    public void feedBytes(byte[] bytes) {
        feed(new String(bytes, StandardCharsets.UTF_8));
    }

    /** 喂入文本/转义序列（增量安全：残缺序列暂存待续） */
    public void feed(String text) {
        pending.append(text);
        String s = pending.toString();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 0x1b) {
                int next = parseEscape(s, i);
                if (next < 0) {
                    break;
                }
                i = next;
            } else {
                print(s.charAt(i));
                i++;
            }
        }
        pending.setLength(0);
        if (i < s.length()) {
            pending.append(s.substring(i));
        }
    }

    private void print(char c) {
        switch (c) {
            case '\n' -> active.lineFeed();
            case '\r' -> active.curCol = 0;
            case '\b' -> active.curCol = active.clampCol(active.curCol - 1);
            case '\t' -> active.curCol = active.clampCol((active.curCol / 8 + 1) * 8);
            default -> {
                if (Grid.isCombining(c)) {
                    return;
                }
                if (Grid.isWide(c)) {
                    active.writeWide(c, currentStyle[0]);
                } else {
                    active.write(c, currentStyle[0]);
                }
            }
        }
    }

    /** 解析 ESC 序列；残缺返回 -1；返回消耗后的下一位置 */
    private int parseEscape(String s, int escPos) {
        if (escPos + 1 >= s.length()) {
            return -1;
        }
        char kind = s.charAt(escPos + 1);
        switch (kind) {
            case '[' -> {
                int i = escPos + 2;
                boolean privateMode = false;
                List<Integer> params = new ArrayList<>();
                StringBuilder num = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == 0x1b) {
                        return i;
                    }
                    if (c == '?') {
                        privateMode = true;
                    } else if (Character.isDigit(c)) {
                        num.append(c);
                    } else if (c == ';') {
                        params.add(num.isEmpty() ? 0 : Integer.parseInt(num.toString()));
                        num.setLength(0);
                    } else {
                        if (!num.isEmpty()) {
                            params.add(Integer.parseInt(num.toString()));
                        }
                        dispatchCsi(privateMode, params, c);
                        return i + 1;
                    }
                    i++;
                }
                return -1;
            }
            case '7' -> {
                saveCursor();
                return escPos + 2;
            }
            case '8' -> {
                restoreCursor();
                return escPos + 2;
            }
            default -> {
                return escPos + 2;
            }
        }
    }

    private void dispatchCsi(boolean privateMode, List<Integer> params, int finalByte) {
        int n1 = params.isEmpty() ? 1 : params.get(0);
        switch (finalByte) {
            case 'H', 'f' -> {
                int row = params.isEmpty() ? 1 : params.get(0);
                int col = params.size() > 1 ? params.get(1) : 1;
                active.setCursor(row - 1, col - 1);
            }
            case 'A' -> active.curRow = active.clampRow(active.curRow - Math.max(1, n1));
            case 'B' -> active.curRow = active.clampRow(active.curRow + Math.max(1, n1));
            case 'C' -> active.curCol = active.clampCol(active.curCol + Math.max(1, n1));
            case 'D' -> active.curCol = active.clampCol(active.curCol - Math.max(1, n1));
            case 'J' -> active.eraseDisplay(params.isEmpty() ? 0 : params.get(0));
            case 'K' -> active.eraseInLine(params.isEmpty() ? 0 : n1);
            case 'r' -> {
                if (!privateMode && params.size() >= 2) {
                    active.setScrollRegion(params.get(0) - 1, params.get(1) - 1);
                }
            }
            case 'm' -> applySgr(params.isEmpty() ? List.of(0) : params);
            case 'h', 'l' -> {
                if (privateMode && params.contains(1049)) {
                    if (finalByte == 'h') {
                        enterAlt();
                    } else {
                        exitAlt();
                    }
                }
            }
            default -> {
                // 未知序列跳过
            }
        }
    }

    private void saveCursor() {
        (altMode ? savedAltCursor : savedMainCursor)[0] = active.curRow;
        (altMode ? savedAltCursor : savedMainCursor)[1] = active.curCol;
    }

    private void restoreCursor() {
        int[] saved = altMode ? savedAltCursor : savedMainCursor;
        active.setCursor(saved[0], saved[1]);
    }

    private void enterAlt() {
        if (altMode) {
            return;
        }
        saveCursor();
        altMode = true;
        active = alt;
        for (Grid.Cell[] row : alt.cells) {
            for (Grid.Cell cell : row) {
                cell.reset();
            }
        }
    }

    private void exitAlt() {
        if (!altMode) {
            return;
        }
        altMode = false;
        active = main;
        restoreCursor();
    }

    /** SGR 应用：0 重置/1·3·4·7 开/21·22·23·24·27 关/30-37·39 前景/40-47·49 背景/38·48 5;n 与 2;r;g;b */
    private void applySgr(List<Integer> params) {
        for (int i = 0; i < params.size(); i++) {
            int p = params.get(i);
            switch (p) {
                case 0 -> currentStyle[0] = Style.DEFAULT;
                case 1 -> currentStyle[0] = currentStyle[0].withBold(true);
                case 3 -> currentStyle[0] = currentStyle[0].withItalic(true);
                case 4 -> currentStyle[0] = currentStyle[0].withUnderline(true);
                case 7 -> currentStyle[0] = currentStyle[0].withReverse(true);
                case 21, 22 -> currentStyle[0] = currentStyle[0].withBold(false);
                case 23 -> currentStyle[0] = currentStyle[0].withItalic(false);
                case 24 -> currentStyle[0] = currentStyle[0].withUnderline(false);
                case 27 -> currentStyle[0] = currentStyle[0].withReverse(false);
                case 39 -> currentStyle[0] = currentStyle[0].withFg(null);
                case 49 -> currentStyle[0] = currentStyle[0].withBg(null);
                default -> {
                    if (p >= 30 && p <= 37) {
                        currentStyle[0] = currentStyle[0].withFg(String.valueOf(p));
                    } else if (p >= 40 && p <= 47) {
                        currentStyle[0] = currentStyle[0].withBg(String.valueOf(p));
                    } else if ((p == 38 || p == 48) && i + 1 < params.size()) {
                        int mode = params.get(i + 1);
                        if (mode == 5 && i + 2 < params.size()) {
                            String token = p + ";5;" + params.get(i + 2);
                            currentStyle[0] = p == 38 ? currentStyle[0].withFg(token)
                                    : currentStyle[0].withBg(token);
                            i += 2;
                        } else if (mode == 2 && i + 4 < params.size()) {
                            String token = p + ";2;" + params.get(i + 2) + ";" + params.get(i + 3)
                                    + ";" + params.get(i + 4);
                            currentStyle[0] = p == 38 ? currentStyle[0].withFg(token)
                                    : currentStyle[0].withBg(token);
                            i += 4;
                        }
                    }
                }
            }
        }
    }

    private void pushScrollback(Grid grid) {
        scrollback.addLast(grid.rowPlain(grid.scrollTop));
        while (scrollback.size() > SCROLLBACK_CAP) {
            scrollback.removeFirst();
        }
    }

    /** 纯文本快照（尾空行与行尾空白裁剪，行间 \n） */
    public String renderPlain() {
        int lastRow = lastContentRow();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= lastRow; r++) {
            if (r > 0) {
                sb.append('\n');
            }
            sb.append(active.rowPlain(r));
        }
        return sb.toString();
    }

    private int lastContentRow() {
        for (int r = active.rows - 1; r >= 0; r--) {
            if (active.lastNonBlank(r) >= 0) {
                return r;
            }
        }
        return -1;
    }

    /** ANSI 重放快照：按样式变化发 SGR，喂回新仿真器文本一致 */
    public String renderAnsi() {
        int lastRow = lastContentRow();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= lastRow; r++) {
            if (r > 0) {
                sb.append("\r\n");
            }
            Style prev = Style.DEFAULT;
            int last = active.lastNonBlank(r);
            for (int c = 0; c <= last; c++) {
                Grid.Cell cell = active.cell(r, c);
                if (cell.wideTail) {
                    continue;
                }
                if (!cell.style.equals(prev)) {
                    sb.append(ESC).append(cell.style.toSgr()).append('m');
                    prev = cell.style;
                }
                sb.append(cell.ch);
            }
            if (!prev.isDefault()) {
                sb.append(ESC).append("0m");
            }
        }
        return sb.toString();
    }
}
