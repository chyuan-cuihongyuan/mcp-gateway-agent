package cn.chyuan.ai.domain.usage.service;

import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import cn.chyuan.ai.domain.usage.model.valobj.DailyUsageVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageQueryVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 用量账本服务（工单 0046）
 *
 * <p>每次治理面放行/拒绝的调用异步落明细 + 日聚合增量 upsert；
 * 落账失败仅 WARN 不阻断请求主链。查询面供 admin API（明细分页/日聚合/趋势）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class UsageLedgerService implements IUsageLedgerService {

    @Resource
    private IUsageRepository repository;

    /** 落账单线程（守护；账本写不与请求主链争抢） */
    private final ExecutorService ledgerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "governance-usage-ledger");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        ledgerExecutor.shutdown();
        try {
            if (!ledgerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                ledgerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ledgerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void record(UsageRecordVO record) {
        if (record == null) {
            return;
        }
        if (record.getRequestId() == null || record.getRequestId().isBlank()) {
            record.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(new java.util.Date());
        }
        DailyUsageVO delta = DailyUsageVO.deltaOf(
                LocalDate.now().toString(),
                record.getVirtualKeyId(),
                record.getToolOrModel(),
                record.getChannelId(),
                "SUCCESS".equals(record.getStatus()),
                record.getDurationMs() == null ? 0 : record.getDurationMs(),
                (record.getPromptTokens() == null ? 0 : record.getPromptTokens())
                        + (record.getCompletionTokens() == null ? 0 : record.getCompletionTokens()));
        try {
            ledgerExecutor.execute(() -> {
                try {
                    repository.insert(record);
                    repository.upsertDaily(delta);
                } catch (Exception e) {
                    log.warn("用量落账失败 requestId={} tool={}：{}", record.getRequestId(),
                            record.getToolOrModel(), e.getMessage());
                }
            });
        } catch (Exception e) {
            // 拒绝策略兜底（队列满）：不阻断主链
            log.warn("用量落账任务提交失败 tool={}：{}", record.getToolOrModel(), e.getMessage());
        }
    }

    @Override
    public List<UsageRecordVO> page(UsageQueryVO query, int page, int size) {
        return repository.page(query, Math.max(page - 1, 0) * size, size);
    }

    @Override
    public long count(UsageQueryVO query) {
        return repository.count(query);
    }

    @Override
    public List<DailyUsageVO> dailyDetail(String fromDate, String toDate) {
        return repository.dailyDetail(fromDate, toDate);
    }

    @Override
    public List<DailyUsageVO> dailyTotals(String fromDate, String toDate) {
        return repository.dailyTotals(fromDate, toDate);
    }
}
