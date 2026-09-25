package cn.chyuan.ai.domain.termkernel.service;

/**
 * 终端网格（工单 0817 CS1/0820 CS4/0821 CS5，alacritty 终端思想）。
 * 行列 cell 模型与光标钳制/写入推进/清屏清行/滚动区域 top·bottom margin/东亚宽字符成对占格/组合字符零格。
 */
public final class Grid {

    public static final class Cell {
        public char ch = ' ';
        public Style style = Style.DEFAULT;
        public boolean wideTail = false;

        public boolean isBlank() {
            return ch == ' ' && style.isDefault() && !wideTail;
        }

        void reset() {
            ch = ' ';
            style = Style.DEFAULT;
            wideTail = false;
        }
    }

    public final int rows;
    public final int cols;
    final Cell[][] cells;
    int curRow = 0;
    int curCol = 0;
    int scrollTop = 0;
    int scrollBottom;
    Runnable onScrollUp;

    public Grid(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("网格行列须为正");
        }
        this.rows = rows;
        this.cols = cols;
        this.scrollBottom = rows - 1;
        this.cells = new Cell[rows][cols];
        for (Cell[] row : cells) {
            for (int c = 0; c < cols; c++) {
                row[c] = new Cell();
            }
        }
    }

    public Cell cell(int row, int col) {
        checkIndex(row, col);
        return cells[row][col];
    }

    private void checkIndex(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IllegalArgumentException("网格越界: " + row + "," + col);
        }
    }

    public void setCursor(int row, int col) {
        this.curRow = clampRow(row);
        this.curCol = clampCol(col);
    }

    int clampRow(int row) {
        return Math.max(0, Math.min(rows - 1, row));
    }

    int clampCol(int col) {
        return Math.max(0, Math.min(cols - 1, col));
    }

    /** 光标推进：行末钳制不换行 */
    void advance() {
        if (curCol < cols - 1) {
            curCol++;
        }
    }

    /** 写普通字符（覆写宽头时清除其尾格） */
    void write(char ch, Style style) {
        if (curCol + 1 < cols && cells[curRow][curCol + 1].wideTail) {
            cells[curRow][curCol + 1].reset();
        }
        Cell cell = cells[curRow][curCol];
        cell.reset();
        cell.ch = ch;
        cell.style = style;
        advance();
    }

    /** 写宽字符：占两格（尾格 wideTail），末列无成对空间则仅写占位 */
    void writeWide(char ch, Style style) {
        if (curCol >= cols - 1) {
            Cell cell = cells[curRow][curCol];
            cell.reset();
            cell.ch = ch;
            cell.style = style;
            return;
        }
        Cell head = cells[curRow][curCol];
        Cell tail = cells[curRow][curCol + 1];
        head.reset();
        head.ch = ch;
        head.style = style;
        tail.reset();
        tail.wideTail = true;
        advance();
        advance();
    }

    void lineFeed() {
        if (curRow == scrollBottom) {
            scrollUp();
        } else {
            curRow = Math.min(curRow + 1, rows - 1);
        }
    }

    /** 区域内上滚：顶行交回调（回看缓冲），余行上移，底行清空 */
    void scrollUp() {
        if (onScrollUp != null) {
            onScrollUp.run();
        }
        for (int r = scrollTop; r < scrollBottom; r++) {
            cells[r] = cells[r + 1];
        }
        cells[scrollBottom] = newRow();
    }

    private Cell[] newRow() {
        Cell[] row = new Cell[cols];
        for (int c = 0; c < cols; c++) {
            row[c] = new Cell();
        }
        return row;
    }

    /** 设置滚动区域（0 基入参，top<bottom 才生效） */
    void setScrollRegion(int top, int bottom) {
        if (top < 0 || bottom >= rows || top >= bottom) {
            return;
        }
        this.scrollTop = top;
        this.scrollBottom = bottom;
    }

    /** ED 清屏：0 光标下方 1 光标上方 2 全屏 */
    void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                eraseLineRange(curRow, curCol, cols - 1);
                for (int r = curRow + 1; r < rows; r++) {
                    clearRow(r);
                }
            }
            case 1 -> {
                for (int r = 0; r < curRow; r++) {
                    clearRow(r);
                }
                eraseLineRange(curRow, 0, curCol);
            }
            default -> {
                for (int r = 0; r < rows; r++) {
                    clearRow(r);
                }
            }
        }
    }

    /** EL 清行：0 至行尾 1 至行头 2 整行 */
    void eraseInLine(int mode) {
        switch (mode) {
            case 0 -> eraseLineRange(curRow, curCol, cols - 1);
            case 1 -> eraseLineRange(curRow, 0, curCol);
            default -> clearRow(curRow);
        }
    }

    private void eraseLineRange(int row, int from, int to) {
        for (int c = from; c <= to && c < cols; c++) {
            cells[row][c].reset();
        }
    }

    private void clearRow(int row) {
        for (Cell c : cells[row]) {
            c.reset();
        }
    }

    /** 该行是否全空（渲染裁剪用） */
    int lastNonBlank(int row) {
        for (int c = cols - 1; c >= 0; c--) {
            if (!cells[row][c].isBlank()) {
                return c;
            }
        }
        return -1;
    }

    String rowPlain(int row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c <= lastNonBlank(row); c++) {
            Cell cell = cells[row][c];
            if (cell.wideTail) {
                continue;
            }
            sb.append(cell.ch);
        }
        return sb.toString();
    }

    static boolean isWide(char ch) {
        return (ch >= 0x1100 && ch <= 0x115F) || (ch >= 0x2E80 && ch <= 0xA4CF)
                || (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0xF900 && ch <= 0xFAFF)
                || (ch >= 0xFE30 && ch <= 0xFE4F) || (ch >= 0xFF00 && ch <= 0xFF60)
                || (ch >= 0xFFE0 && ch <= 0xFFE6) || (ch >= 0x20000 && ch <= 0x2FFFD);
    }

    static boolean isCombining(char ch) {
        int type = Character.getType(ch);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }
}
