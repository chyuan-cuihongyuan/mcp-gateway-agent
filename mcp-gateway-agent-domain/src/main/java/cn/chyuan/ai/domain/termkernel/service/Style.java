package cn.chyuan.ai.domain.termkernel.service;

/**
 * 单元样式（工单 0819 CS3，alacritty 终端思想）。
 * 前景背景色（基础 16 编码片段/256 色/RGB 记为规范 SGR 片段）/粗体斜体下划线反转/不可变 wither。
 */
public record Style(String fg, String bg, boolean bold, boolean italic, boolean underline, boolean reverse) {

    public static final Style DEFAULT = new Style(null, null, false, false, false, false);

    public Style(String fg, String bg, boolean bold, boolean italic, boolean underline, boolean reverse) {
        this.fg = fg;
        this.bg = bg;
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.reverse = reverse;
    }

    public boolean isDefault() {
        return fg == null && bg == null && !bold && !italic && !underline && !reverse;
    }

    Style withFg(String fg) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    Style withBg(String bg) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    Style withBold(boolean bold) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    Style withItalic(boolean italic) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    Style withUnderline(boolean underline) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    Style withReverse(boolean reverse) {
        return new Style(fg, bg, bold, italic, underline, reverse);
    }

    /** 还原为 SGR 参数序列（无属性即 0 重置） */
    public String toSgr() {
        if (isDefault()) {
            return "0";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (bold) {
            parts.add("1");
        }
        if (italic) {
            parts.add("3");
        }
        if (underline) {
            parts.add("4");
        }
        if (reverse) {
            parts.add("7");
        }
        if (fg != null) {
            parts.add(fg);
        }
        if (bg != null) {
            parts.add(bg);
        }
        return String.join(";", parts);
    }
}
