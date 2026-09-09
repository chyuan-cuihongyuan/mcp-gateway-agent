package cn.chyuan.ai.cases.admin.governance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账单导出 CSV 转义测试（工单 0090）
 */
@DisplayName("账单 CSV 转义测试")
class BillingCsvTest {

    @Test
    @DisplayName("RFC 4180：逗号/引号/换行整字段包裹，内部引号翻倍")
    void csvCellEscaping() {
        Assertions.assertEquals("", AdminGovernanceService.csvCell(null));
        Assertions.assertEquals("普通模型", AdminGovernanceService.csvCell("普通模型"));
        Assertions.assertEquals("\"a,b\"", AdminGovernanceService.csvCell("a,b"));
        Assertions.assertEquals("\"说\"\"你好\"\"\"", AdminGovernanceService.csvCell("说\"你好\""));
        Assertions.assertEquals("\"第一行\n第二行\"", AdminGovernanceService.csvCell("第一行\n第二行"));
        Assertions.assertEquals("\"回\r车\"", AdminGovernanceService.csvCell("回\r车"));
    }
}
