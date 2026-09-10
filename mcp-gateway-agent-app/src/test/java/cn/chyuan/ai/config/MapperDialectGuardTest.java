package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mapper 方言静态断言（工单 0126，三期 PG 双轨）：
 * ①无 databaseId 的公共语句禁用双方言专有模式（MySQL：ON DUPLICATE KEY/INSERT IGNORE/
 *   DATE_FORMAT/DATE_ADD/FIND_IN_SET/GROUP_CONCAT/反引号/两参 LIMIT；PG：ON CONFLICT/to_char/make_interval）；
 * ②databaseId=mysql 语句禁 PG 模式；databaseId=postgresql 语句禁 MySQL 模式；
 * ③分叉语句必须成对（同 namespace+id 同时存在 mysql 与 postgresql 版本）。
 */
class MapperDialectGuardTest {

    /** MySQL 专有模式（公共语句/PG 分叉语句中出现即违规） */
    private static final Pattern[] MYSQL_ONLY = {
            Pattern.compile("ON\\s+DUPLICATE\\s+KEY", Pattern.CASE_INSENSITIVE),
            Pattern.compile("INSERT\\s+IGNORE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE_FORMAT\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE_ADD\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("FIND_IN_SET\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("GROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("IFNULL\\s*\\(", Pattern.CASE_INSENSITIVE),
            // MySQL 专有的 upsert 列引用形式 "= VALUES(col)"（INSERT 的 VALUES 子句双方言合法，不在此列）
            Pattern.compile("=\\s*VALUES\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("LIMIT\\s*#\\{[^}]+}\\s*,"),
            Pattern.compile("`")
    };

    /** PG 专有模式（公共语句/MySQL 分叉语句中出现即违规） */
    private static final Pattern[] PG_ONLY = {
            Pattern.compile("ON\\s+CONFLICT", Pattern.CASE_INSENSITIVE),
            Pattern.compile("to_char\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("make_interval\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("::halfvec\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("LIMIT\\s+\\d+\\s+OFFSET", Pattern.CASE_INSENSITIVE)
    };

    private static final String MAPPER_DIR = "src/main/resources/mybatis/mapper";

    @Test
    void gatewayMappersRespectDialectRules() throws Exception {
        File dir = new File(MAPPER_DIR);
        assertTrue(dir.isDirectory(), "mapper 目录应存在: " + MAPPER_DIR);

        List<String> violations = new ArrayList<>();
        // namespace+id -> 出现过的 databaseId 集合（分叉成对校验）
        Map<String, Set<String>> dialectsById = new HashMap<>();

        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        for (File file : files) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(file);
            String namespace = document.getDocumentElement().getAttribute("namespace");

            NodeList statements = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < statements.getLength(); i++) {
                if (!(statements.item(i) instanceof Element element)) {
                    continue;
                }
                String tag = element.getTagName();
                if (!tag.equals("select") && !tag.equals("insert") && !tag.equals("update") && !tag.equals("delete")) {
                    continue;
                }
                String id = element.getAttribute("id");
                String databaseId = element.getAttribute("databaseId");
                String sql = element.getTextContent();

                dialectsById.computeIfAbsent(namespace + "." + id, k -> new HashSet<>()).add(databaseId);

                if (databaseId.isEmpty()) {
                    checkPatterns(file, id, sql, MYSQL_ONLY, "MySQL 专有模式不应出现在公共语句", violations);
                    checkPatterns(file, id, sql, PG_ONLY, "PG 专有模式不应出现在公共语句", violations);
                } else if (databaseId.equals("mysql")) {
                    checkPatterns(file, id, sql, PG_ONLY, "PG 专有模式不应出现在 mysql 分叉语句", violations);
                } else if (databaseId.equals("postgresql")) {
                    checkPatterns(file, id, sql, MYSQL_ONLY, "MySQL 专有模式不应出现在 postgresql 分叉语句", violations);
                }
            }
        }

        // 分叉成对：带 databaseId 的语句，mysql 与 postgresql 必须同时存在
        dialectsById.forEach((statementId, dialects) -> {
            if (dialects.contains("mysql") && !dialects.contains("postgresql")) {
                violations.add(statementId + "：mysql 分叉缺少 postgresql 配对");
            }
            if (dialects.contains("postgresql") && !dialects.contains("mysql")) {
                violations.add(statementId + "：postgresql 分叉缺少 mysql 配对");
            }
        });

        assertTrue(violations.isEmpty(), "mapper 方言违规:\n" + String.join("\n", violations));
    }

    private void checkPatterns(File file, String id, String sql, Pattern[] patterns,
            String message, List<String> violations) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(sql).find()) {
                violations.add(file.getName() + "#" + id + "：" + message + "（命中 " + pattern + "）");
            }
        }
    }
}
