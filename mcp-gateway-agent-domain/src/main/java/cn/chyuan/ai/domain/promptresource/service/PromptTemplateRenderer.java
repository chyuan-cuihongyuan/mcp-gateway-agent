package cn.chyuan.ai.domain.promptresource.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示模板渲染器（工单 0198 AA3，借鉴 Dify prompt IDE）—
 * 纯函数内核：{{var}} 占位符提取、变量缺失校验、嵌套占位检测、渲染 + LRU 缓存
 * （模板+变量哈希为键）。与 PromptResourceService.renderTemplate 的"缺参保留原样"
 * 调试语义不同，本渲染器是严格语义：缺参即报错（发布前校验用）。
 *
 * @author chyuan
 */
@Service
public class PromptTemplateRenderer {

    /** 严格占位符：{{var}}，变量名字母数字下划线 */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    /** LRU 缓存默认容量 */
    static final int DEFAULT_CACHE_SIZE = 256;

    private final LinkedHashMap<String, String> cache;

    public PromptTemplateRenderer() {
        this(DEFAULT_CACHE_SIZE);
    }

    /** 测试专用：显式容量 */
    public PromptTemplateRenderer(int cacheSize) {
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        };
    }

    /** 提取模板声明的占位符变量名（按出现顺序去重） */
    public static Set<String> extractVariables(String template) {
        Set<String> vars = new LinkedHashSet<>();
        if (template == null) {
            return vars;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return vars;
    }

    /**
     * 校验：返回错误列表（空=通过）。规则：
     * ① 结构检测——剔除全部合法占位符后仍残留 {{ 或 }}，即嵌套/悬挂占位（形如 {{a{{b}}}}）；
     * ② 缺失变量——声明占位符在 variables 中缺失；
     * ③ 多余变量——variables 中模板未声明的键（警告级，同样计入错误列表返回）。
     */
    public static java.util.List<String> validate(String template, Set<String> variableNames) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (template == null || template.isBlank()) {
            errors.add("模板为空");
            return errors;
        }
        String residue = PLACEHOLDER.matcher(template).replaceAll(" ");
        if (residue.contains("{{") || residue.contains("}}")) {
            errors.add("检测到嵌套/悬挂占位符，模板结构非法");
        }
        Set<String> declared = extractVariables(template);
        for (String var : declared) {
            if (variableNames == null || !variableNames.contains(var)) {
                errors.add("缺少变量: " + var);
            }
        }
        if (variableNames != null) {
            for (String given : variableNames) {
                if (!declared.contains(given)) {
                    errors.add("多余变量: " + given);
                }
            }
        }
        return errors;
    }

    /** 严格渲染：变量不齐抛 IllegalArgumentException（先 validate 可拿到具体错误） */
    public static String renderStrict(String template, Map<String, String> variables) {
        Set<String> declared = extractVariables(template);
        Map<String, String> vars = variables == null ? Map.of() : variables;
        for (String var : declared) {
            if (!vars.containsKey(var) || vars.get(var) == null) {
                throw new IllegalArgumentException("缺少变量: " + var);
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(vars.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 带缓存的渲染入口：先校验后渲染；错误抛 IllegalArgumentException（消息=错误列表拼接） */
    public String render(String template, Map<String, String> variables) {
        Set<String> varNames = variables == null ? Set.of() : variables.keySet();
        String cacheKey = template + "§" + new java.util.TreeSet<>(varNames) + "§"
                + (variables == null ? "" : variables);
        synchronized (cache) {
            String cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        java.util.List<String> errors = validate(template, varNames);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        String rendered = renderStrict(template, variables);
        synchronized (cache) {
            cache.put(cacheKey, rendered);
        }
        return rendered;
    }

    /** 当前缓存条数（观测用） */
    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
