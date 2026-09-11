package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预算滚动窗口起点纯函数测试（工单 0158：跨窗口边界/周起点口径/月长不一）
 */
@DisplayName("预算滚动窗口起点纯函数测试")
public class QuotaWindowsTest {

    private static Date date(LocalDateTime local) {
        return Date.from(local.atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("日窗口 — 起点 = now - 24h（跨日边界回溯）")
    public void testDayWindow() {
        Date now = date(LocalDateTime.of(2026, 9, 11, 10, 0, 0));
        Date start = QuotaWindows.startOf(now, QuotaWindows.WINDOW_DAY);
        assertEquals(date(LocalDateTime.of(2026, 9, 10, 10, 0, 0)), start);
    }

    @Test
    @DisplayName("周窗口 — 起点 = now - 7d 滚动口径，而非自然周周一")
    public void testWeekWindowRollingNotCalendar() {
        // 2026-09-11 是周五；滚动周窗起点应为上周五 10:00，而非周一
        Date now = date(LocalDateTime.of(2026, 9, 11, 10, 0, 0));
        Date start = QuotaWindows.startOf(now, QuotaWindows.WINDOW_WEEK);
        assertEquals(date(LocalDateTime.of(2026, 9, 4, 10, 0, 0)), start);
        assertNotEquals(date(LocalDateTime.of(2026, 9, 7, 0, 0, 0)), start, "不得取自然周周一");
    }

    @Test
    @DisplayName("月窗口 — 自然月回溯，月长不一自动处理（3/31 回溯→2/28）")
    public void testMonthWindowVariableLength() {
        Date mar31 = date(LocalDateTime.of(2026, 3, 31, 12, 0, 0));
        Date start = QuotaWindows.startOf(mar31, QuotaWindows.WINDOW_MONTH);
        assertEquals(date(LocalDateTime.of(2026, 2, 28, 12, 0, 0)), start, "1 个月回溯落在 2 月末");

        Date jul15 = date(LocalDateTime.of(2026, 7, 15, 0, 0, 0));
        assertEquals(date(LocalDateTime.of(2026, 6, 15, 0, 0, 0)),
                QuotaWindows.startOf(jul15, QuotaWindows.WINDOW_MONTH));
    }

    @Test
    @DisplayName("未配置/未知类型 — 返回 null（调用方沿用旧固定窗口行为）")
    public void testUnconfiguredAndUnknown() {
        Date now = new Date();
        assertNull(QuotaWindows.startOf(now, null));
        assertNull(QuotaWindows.startOf(now, "  "));
        assertNull(QuotaWindows.startOf(now, "QUARTER"));
        assertFalse(QuotaWindows.isSliding(null));
        assertFalse(QuotaWindows.isSliding("QUARTER"));
    }

    @Test
    @DisplayName("大小写归一与跨年/跨月边界回溯")
    public void testNormalizationAndYearBoundary() {
        // 2026-01-31 月窗回溯 → 2025-12-31（跨自然年 + 月长不一钳制到月末）
        Date jan31 = date(LocalDateTime.of(2026, 1, 31, 8, 0, 0));
        assertEquals(date(LocalDateTime.of(2025, 12, 31, 8, 0, 0)),
                QuotaWindows.startOf(jan31, "MONTH"));
        // 2026-03-01 月窗回溯 → 2026-02-01（跨月边界）
        Date mar1 = date(LocalDateTime.of(2026, 3, 1, 8, 0, 0));
        assertEquals(date(LocalDateTime.of(2026, 2, 1, 8, 0, 0)),
                QuotaWindows.startOf(mar1, "month"));
        assertTrue(QuotaWindows.isSliding("day"));
        assertTrue(QuotaWindows.isSliding("Week"));
    }
}
