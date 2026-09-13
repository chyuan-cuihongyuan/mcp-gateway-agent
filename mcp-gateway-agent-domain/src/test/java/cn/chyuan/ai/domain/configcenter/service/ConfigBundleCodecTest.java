package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.BundleEntry;
import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.ConfigBundle;
import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.ImportReport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置 bundle 编解码单测（工单 0259 AG9）：往返一致/校验和防篡改/冲突策略/敏感密文不出站。
 */
class ConfigBundleCodecTest {

    @Test
    void 导出确定性键序与往返一致() {
        ConfigBundle bundle = ConfigBundleCodec.export("ns", List.of(
                new BundleEntry("b", 2, "vb", "mb", false),
                new BundleEntry("a", 1, "va", "ma", true)));
        // 键序确定性（重复导出逐字节一致）
        String json1 = ConfigBundleCodec.toJson(bundle);
        String json2 = ConfigBundleCodec.toJson(ConfigBundleCodec.export("ns", List.of(
                new BundleEntry("a", 1, "va", "ma", true),
                new BundleEntry("b", 2, "vb", "mb", false))));
        assertEquals(json1, json2);
        // 反序列化往返
        ConfigBundle parsed = ConfigBundleCodec.fromJson(json1);
        assertEquals(bundle.checksum(), parsed.checksum());
        assertEquals(2, parsed.entries().size());
        // 敏感项密文原样出站（导出侧不解密——调用方传什么就带什么）
        assertTrue(parsed.entries().stream().filter(e -> e.configKey().equals("a"))
                .findFirst().orElseThrow().sensitive());
    }

    @Test
    void 校验和防篡改() {
        ConfigBundle bundle = ConfigBundleCodec.export("ns", List.of(
                new BundleEntry("a", 1, "va", "ma", false)));
        String tampered = ConfigBundleCodec.toJson(bundle).replace("va", "evil");
        Map<String, String> existing = new HashMap<>();
        ImportReport report = ConfigBundleCodec.importBundle(ConfigBundleCodec.fromJson(tampered),
                existing, "overwrite", (action, entry) -> existing.put(entry.configKey(), entry.content()));
        assertFalse(report.success());
        assertTrue(report.errors().get(0).contains("校验和"));
        assertTrue(existing.isEmpty());
    }

    @Test
    void 冲突策略三分支() {
        ConfigBundle bundle = ConfigBundleCodec.export("ns", List.of(
                new BundleEntry("new", 1, "vn", "mn", false),
                new BundleEntry("same", 1, "vs", "ms", false),
                new BundleEntry("diff", 1, "vd", "md", false)));
        Map<String, String> existing = new HashMap<>();
        existing.put("same", "vs");
        existing.put("diff", "other");
        AtomicInteger applied = new AtomicInteger();
        // skip 策略：diff 冲突跳过（same 内容一致也跳过）
        ImportReport skipped = ConfigBundleCodec.importBundle(bundle, existing, "skip",
                (action, entry) -> applied.incrementAndGet());
        assertEquals(1, skipped.added());
        assertEquals(2, skipped.skipped());
        assertEquals(0, skipped.overwritten());
        assertTrue(skipped.success());
        // overwrite 策略：内容一致的 same 仍计 skip（无需覆盖），diff 被覆盖
        ImportReport overwrote = ConfigBundleCodec.importBundle(bundle, existing, "overwrite",
                (action, entry) -> applied.incrementAndGet());
        assertEquals(1, overwrote.skipped());
        assertEquals(1, overwrote.overwritten());
        assertEquals(2, overwrote.added() + overwrote.overwritten());
        // 非法策略拒绝
        ImportReport illegal = ConfigBundleCodec.importBundle(bundle, existing, "bogus",
                (action, entry) -> { });
        assertFalse(illegal.success());
    }

    @Test
    void 非法结构拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ConfigBundleCodec.fromJson("not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigBundleCodec.fromJson("{\"entries\":[]}"));
    }

    @Test
    void 规范化载荷与键序无关() {
        List<BundleEntry> a = List.of(new BundleEntry("x", 1, "c", "m", false));
        List<BundleEntry> b = List.of(new BundleEntry("x", 1, "c", "m", false));
        assertEquals(ConfigBundleCodec.canonical("ns", a), ConfigBundleCodec.canonical("ns", b));
        // sortedCopy 便利方法（TreeMap 键序）
        assertEquals(1, ConfigBundleCodec.sortedCopy(new TreeMap<>(Map.of("k", "v"))).size());
    }
}
