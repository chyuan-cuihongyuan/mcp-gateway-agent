package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IGuardrailRepository;
import cn.chyuan.ai.domain.governance.cache.TtlCache;
import cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 治理护栏执行链（工单 0091，LiteLLM guardrails mode 语义裁剪）
 *
 * <p>按 priority 顺序对文本载荷执行启用的护栏：BLOCK 即短路；MASK 改写后继续。
 * 内置规则求值器：PII_MASK（内置四类正则脱敏）、KEYWORD_BLOCK / REGEX_BLOCK（命中阻断）、
 * RESPONSE_FILTER / RESPONSE_MASK（响应侧同引擎，0094 挂点消费）。30s TTL 快照 +
 * 写操作即时失效本实例（与 CEL 快照同口径）；热更新广播由 0078 兜底。
 *
 * <p>兼容红线：无护栏配置时行为与现状完全一致（零规则零开销直通）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class GuardrailChain {

    /** PII 内置正则（工单 0092；掩码占位 [PII:类型]） */
    static final Map<String, Pattern> PII_PATTERNS = Map.of(
            "phone", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
            "email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            "idcard", Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)"),
            "bankcard", Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)"));

    /** 护栏求值结果 */
    public record GuardrailOutcome(boolean blocked, boolean masked, String hitRule, String text) {

        static GuardrailOutcome pass(String text) {
            return new GuardrailOutcome(false, false, null, text);
        }
    }

    @Resource
    private IGuardrailRepository repository;

    @Value("${governance.guardrail.snapshot-ttl-seconds:30}")
    private long snapshotTtlSeconds;

    private TtlCache<List<GuardrailVO>> snapshotCache;

    @PostConstruct
    void init() {
        snapshotCache = new TtlCache<>(snapshotTtlSeconds * 1000);
    }

    /**
     * 执行护栏链（PRE_CALL / POST_CALL / LOGGING_ONLY 由挂点按 mode 选入）。
     *
     * @param traffic 流量面（MCP / LLM）
     * @param mode    挂点阶段
     * @param text    文本载荷（请求或响应文本）
     */
    public GuardrailOutcome evaluate(String traffic, String mode, String text) {
        if (text == null || text.isEmpty()) {
            return GuardrailOutcome.pass(text);
        }
        boolean anyMasked = false;
        for (GuardrailVO rule : snapshotOf(traffic, mode)) {
            MatcherOutcome outcome = evaluateRule(rule, text);
            if (outcome.blocked()) {
                return new GuardrailOutcome(true, false, rule.getName(), text);
            }
            if (outcome.masked()) {
                anyMasked = true;
                text = outcome.text();
            }
        }
        return new GuardrailOutcome(false, anyMasked, null, text);
    }

    /** 便捷判定：是否命中阻断（供三面挂点快速短路，错误码 -32018） */
    public void assertAllowed(String traffic, String mode, String text) {
        GuardrailOutcome outcome = evaluate(traffic, mode, text);
        if (outcome.blocked()) {
            throw new AppException(McpErrorCodes.CONTENT_BLOCKED,
                    "内容命中安全护栏：" + outcome.hitRule());
        }
    }

    /** 写操作后失效本实例快照（admin CRUD 调用） */
    public void invalidateSnapshot() {
        if (snapshotCache != null) {
            snapshotCache.invalidateAll();
        }
    }

    /** 逐条命中明细（dry-run 面板与测试消费；不改输入） */
    public List<String> explainHits(String traffic, String mode, String text) {
        return snapshotOf(traffic, mode).stream()
                .filter(rule -> evaluateRule(rule, text).matched())
                .map(GuardrailVO::getName)
                .toList();
    }

    private List<GuardrailVO> snapshotOf(String traffic, String mode) {
        return snapshotCache.get("active", () -> repository.findAll().stream()
                .filter(vo -> Integer.valueOf(1).equals(vo.getEnabled()))
                .filter(vo -> vo.appliesToTraffic(traffic))
                .filter(vo -> mode.equals(vo.getMode()))
                .toList());
    }

    private record MatcherOutcome(boolean blocked, boolean masked, boolean matched, String text) {
    }

    /** 单规则求值：按 type 分派；非法配置跳过并告警（不 fail 流量） */
    private MatcherOutcome evaluateRule(GuardrailVO rule, String text) {
        try {
            JSONObject config = rule.getConfig() == null || rule.getConfig().isBlank()
                    ? new JSONObject() : JSON.parseObject(rule.getConfig());
            return switch (rule.getType() == null ? "" : rule.getType()) {
                case GuardrailVO.TYPE_PII_MASK, GuardrailVO.TYPE_RESPONSE_MASK -> maskPii(config, text);
                case GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.TYPE_RESPONSE_FILTER -> keywordBlock(config, text);
                case GuardrailVO.TYPE_REGEX_BLOCK -> regexBlock(config, text);
                default -> new MatcherOutcome(false, false, false, text);
            };
        } catch (Exception e) {
            log.warn("护栏求值异常（跳过该规则，不 fail 流量）rule={}：{}", rule.getName(), e.getMessage());
            return new MatcherOutcome(false, false, false, text);
        }
    }

    private MatcherOutcome maskPii(JSONObject config, String text) {
        boolean masked = false;
        String result = text;
        // 固定顺序：长模式在前（idcard 先于 bankcard/phone，避免长数字被短模式抢先吞掉）
        for (String key : new String[] {"idcard", "bankcard", "phone", "email"}) {
            Boolean toggle = config.getBoolean(key);
            if (Boolean.FALSE.equals(toggle)) {
                continue;
            }
            Matcher matcher = PII_PATTERNS.get(key).matcher(result);
            if (matcher.find()) {
                masked = true;
                result = matcher.replaceAll("[PII:" + key + "]");
            }
        }
        return new MatcherOutcome(false, masked, masked, result);
    }

    private MatcherOutcome keywordBlock(JSONObject config, String text) {
        JSONArray keywords = config.getJSONArray("keywords");
        if (keywords == null) {
            return new MatcherOutcome(false, false, false, text);
        }
        for (Object keyword : keywords) {
            if (keyword != null && text.contains(String.valueOf(keyword))) {
                return new MatcherOutcome(true, false, true, text);
            }
        }
        return new MatcherOutcome(false, false, false, text);
    }

    private MatcherOutcome regexBlock(JSONObject config, String text) {
        JSONArray patterns = config.getJSONArray("patterns");
        if (patterns == null) {
            return new MatcherOutcome(false, false, false, text);
        }
        for (Object pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            if (Pattern.compile(String.valueOf(pattern)).matcher(text).find()) {
                return new MatcherOutcome(true, false, true, text);
            }
        }
        return new MatcherOutcome(false, false, false, text);
    }
}
