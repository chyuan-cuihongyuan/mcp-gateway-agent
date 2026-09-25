package cn.chyuan.ai.domain.termkernel.service;

import java.util.List;

/**
 * 终端端口（工单 0824 CS8，alacritty 思想）。
 * write·feed·render 入口统一编排/与 streamkernel 只读联动（SSE 帧文本拼接为终端写入流形态，不 import）/
 * term-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TermPort {

    /** 写入并解析（转义序列生效） */
    void feed(String text);

    /** UTF-8 字节流写入 */
    void feedBytes(byte[] bytes);

    /** streamkernel 只读联动形态：SSE 帧列表拼接 data 载荷为写入流（帧形状不 import streamkernel） */
    void feedFrames(List<String> sseFrames);

    /** 纯文本快照 */
    String renderPlain();

    /** ANSI 重放快照 */
    String renderAnsi();

    /** 回看缓冲 */
    List<String> scrollback();

    static TermPort inMemory(int rows, int cols) {
        return new InMemoryTerm(rows, cols);
    }
}

final class InMemoryTerm implements TermPort {

    private final TermEmulator emulator;

    InMemoryTerm(int rows, int cols) {
        this.emulator = new TermEmulator(rows, cols);
    }

    @Override
    public void feed(String text) {
        emulator.feed(text);
    }

    @Override
    public void feedBytes(byte[] bytes) {
        emulator.feedBytes(bytes);
    }

    @Override
    public void feedFrames(List<String> sseFrames) {
        StringBuilder buffer = new StringBuilder();
        for (String frame : sseFrames) {
            if (frame != null && frame.startsWith("data:")) {
                buffer.append(frame.substring(5).stripLeading());
            }
        }
        emulator.feed(buffer.toString());
    }

    @Override
    public String renderPlain() {
        return emulator.renderPlain();
    }

    @Override
    public String renderAnsi() {
        return emulator.renderAnsi();
    }

    @Override
    public List<String> scrollback() {
        return emulator.scrollback();
    }
}
