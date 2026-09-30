package cn.chyuan.ai.domain.objkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对象存储内核测试（工单 1127-1134 EW1-EW8，minio 思想）。
 * bucket 规则/对象读写 ETag/版本化/生命周期过期/分片上传/presigned/列举折叠/端口组合管线。
 */
class ObjKernelTest {

    @Test
    void bucketLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> Buckets.validateName("ab"), "过短拒绝");
        assertThrows(IllegalArgumentException.class, () -> Buckets.validateName("Upper"), "大写拒绝");
        assertThrows(IllegalArgumentException.class, () -> Buckets.validateName("a..b"), "连续点拒绝");
        assertThrows(IllegalArgumentException.class, () -> Buckets.validateName("-lead"), "首字符非字母数字拒绝");

        ObjPort port = ObjPort.inMemory();
        port.createBucket("media");
        port.createBucket("artifacts");
        assertThrows(IllegalArgumentException.class, () -> port.createBucket("media"), "重复 bucket 拒绝");
        assertEquals(List.of("artifacts", "media"), port.buckets(), "字典序列表");
        port.put("media", "a.txt", "x");
        assertThrows(IllegalStateException.class, () -> port.deleteBucket("media"), "非空桶删除拒绝");
        port.deleteObject("media", "a.txt");
        port.deleteBucket("media");
        assertEquals(List.of("artifacts"), port.buckets());
        assertThrows(IllegalArgumentException.class, () -> port.put("ghost", "k", "v"), "未知 bucket 拒绝");
    }

    @Test
    void objectReadWrite() {
        assertEquals(Objects.etagOf("hello"), Objects.etagOf("hello"), "同内容同 ETag");
        assertNotEquals(Objects.etagOf("hello"), Objects.etagOf("hellp"));

        ObjPort port = ObjPort.inMemory();
        port.createBucket("docs");
        String etag1 = port.put("docs", "readme", "hello");
        assertEquals("hello", port.get("docs", "readme"), "GET 返回内容一致");
        String etag2 = port.put("docs", "readme", "hello v2");
        assertEquals("hello v2", port.get("docs", "readme"), "覆盖更新内容");
        assertNotEquals(etag1, etag2, "覆盖更新换 ETag");
        assertThrows(IllegalArgumentException.class, () -> port.get("docs", "ghost"), "未知对象拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.put("docs", "big", "x".repeat((int) Objects.MAX_SIZE + 1)), "超大小上限拒绝");
    }

    @Test
    void versioning() {
        ObjPort port = ObjPort.inMemory();
        port.createBucket("plain");
        port.put("plain", "k", "v1");
        port.put("plain", "k", "v2");
        assertEquals("v2", port.get("plain", "k"));
        assertEquals(1, port.list("plain", null, null, 10).keys().size());

        port.createBucket("vault");
        port.enableVersioning("vault");
        port.put("vault", "cfg", "v1");
        long v1 = 1;
        port.put("vault", "cfg", "v2");
        assertEquals("v2", port.get("vault", "cfg"));
        assertEquals("v1", port.getAt("vault", "cfg", v1), "按 versionId 检索旧版");
        port.deleteObject("vault", "cfg");
        assertThrows(IllegalArgumentException.class, () -> port.get("vault", "cfg"), "删除标记后 GET 拒绝");
        assertEquals("v1", port.getAt("vault", "cfg", v1), "删除标记下旧版仍可按版本检索");
        assertTrue(port.list("vault", null, null, 10).keys().isEmpty(), "列举跳过删除标记最新版");
    }

    @Test
    void lifecycleRules() {
        LifecycleRules rules = new LifecycleRules();
        rules.add("tmp/", 7);
        assertThrows(IllegalStateException.class, () -> rules.add("tmp/", 3), "同前缀规则冲突拒绝");
        assertThrows(IllegalArgumentException.class, () -> rules.add("bad", 0), "非正天数拒绝");
        assertFalse(rules.matches("keep/a"), "无规则前缀不匹配");
        assertTrue(rules.matches("tmp/a"));
        assertFalse(rules.expired("tmp/a", 1, 7), "未到期不清理");
        assertTrue(rules.expired("tmp/a", 1, 8), "到期判定");

        ObjPort port = ObjPort.inMemory();
        port.createBucket("app");
        port.rule("tmp/", 7);
        port.put("app", "tmp/old", "x");
        port.put("app", "keep/forever", "y");
        port.setTick(8);
        List<String> swept = port.sweep();
        assertEquals(List.of("tmp/old"), swept, "只清理过期对象");
        assertThrows(IllegalArgumentException.class, () -> port.get("app", "tmp/old"));
        assertEquals("y", port.get("app", "keep/forever"), "无规则对象不清理");
    }

    @Test
    void multipartUpload() {
        MultipartUploads multiparts = new MultipartUploads();
        String id = multiparts.init("big.bin");
        assertThrows(IllegalArgumentException.class, () -> multiparts.uploadPart("ghost", 1, "x"), "未知 uploadId 拒绝");
        assertThrows(IllegalStateException.class, () -> multiparts.uploadPart(id, 2, "x"), "分片编号须从 1 连续");
        multiparts.uploadPart(id, 1, "part-1|");
        multiparts.uploadPart(id, 2, "part-2");
        assertThrows(IllegalStateException.class, () -> multiparts.uploadPart(id, 2, "dup"), "重复编号拒绝");
        MultipartUploads.Merged merged = multiparts.complete(id);
        assertEquals("big.bin", merged.key());
        assertEquals("part-1|part-2", merged.content());
        assertEquals(Objects.etagOf("part-1|part-2"), Objects.etagOf(merged.content()));
        assertThrows(IllegalArgumentException.class, () -> multiparts.complete(id), "完成后会话终结拒绝");

        ObjPort port = ObjPort.inMemory();
        port.createBucket("bulk");
        String upload = port.initUpload("bulk", "videos/movie.mp4");
        port.uploadPart(upload, 1, "AAAA");
        port.uploadPart(upload, 2, "BBBB");
        String etag = port.completeUpload("bulk", upload);
        assertEquals(etag, port.put("bulk", "probe", "AAAABBBB"), "合并内容与直传同 ETag");
        String aborted = port.initUpload("bulk", "x");
        port.abortUpload(aborted);
        assertThrows(IllegalArgumentException.class, () -> port.completeUpload("bulk", aborted), "中止后合并拒绝");
    }

    @Test
    void presignedUrls() {
        PresignedUrls presigner = new PresignedUrls("secret");
        PresignedUrls.Ticket ticket = presigner.sign("GET", "docs", "readme", 0, 10);
        presigner.verify(ticket, "GET", 9);
        assertTrue(ticket.serialize().contains("X-Amz-Signature="), "票据串查询形态");
        assertThrows(IllegalArgumentException.class, () -> presigner.verify(ticket, "GET", 10), "过期拒绝");
        assertThrows(IllegalArgumentException.class, () -> presigner.verify(ticket, "PUT", 5), "方法不匹配拒绝");
        PresignedUrls.Ticket tampered = new PresignedUrls.Ticket("GET", "docs", "other", ticket.expireTick(), ticket.signature());
        assertThrows(IllegalArgumentException.class, () -> presigner.verify(tampered, "GET", 5), "参数篡改拒绝");
        assertThrows(IllegalArgumentException.class, () -> presigner.sign("GET", "docs", "x", 10, 10), "过期时刻必须在未来");

        ObjPort port = ObjPort.inMemory();
        port.createBucket("docs");
        port.put("docs", "readme", "hello");
        PresignedUrls.Ticket url = port.presign("docs", "readme", 10);
        port.verifyPresign(url, "GET");
        port.setTick(10);
        assertThrows(IllegalArgumentException.class, () -> port.verifyPresign(url, "GET"), "虚拟时钟到期拒绝");
    }

    @Test
    void listing() {
        assertEquals(List.of(), ObjectListing.list(List.of(), null, null, 10).keys(), "空桶空列表");
        ObjectListing.Result plain = ObjectListing.list(List.of("b", "a", "c"), null, null, 10);
        assertEquals(List.of("a", "b", "c"), plain.keys(), "字典序");
        assertFalse(plain.isTruncated());

        ObjectListing.Result folded = ObjectListing.list(
                List.of("a/1", "a/2", "b/3", "top.txt"), null, "/", 10);
        assertEquals(List.of("top.txt"), folded.keys());
        assertEquals(List.of("a/", "b/"), folded.commonPrefixes(), "delimiter 折叠公共前缀");

        ObjectListing.Result paged = ObjectListing.list(
                List.of("a/1", "a/2", "b/3", "top.txt"), null, "/", 2);
        assertEquals(List.of("a/", "b/"), paged.commonPrefixes(), "keys 与公共前缀合用 maxKeys 预算");
        assertTrue(paged.isTruncated(), "maxKeys 截断");
        assertEquals("b/", paged.nextMarker());
        assertThrows(IllegalArgumentException.class, () -> ObjectListing.list(List.of(), null, null, 0), "maxKeys 非正拒绝");

        ObjectListing.Result prefixed = ObjectListing.list(
                List.of("log/a", "log/b", "tmp/c"), "log/", null, 10);
        assertEquals(List.of("log/a", "log/b"), prefixed.keys(), "前缀过滤");
    }

    @Test
    void portPipeline() {
        ObjPort port = ObjPort.inMemory();
        port.createBucket("media");
        port.enableVersioning("media");
        port.rule("tmp/", 5);
        port.put("media", "tmp/x", "1");
        port.put("media", "docs/y", "2");
        port.setTick(5);
        assertEquals(List.of("tmp/x"), port.sweep(), "组合管线生命周期清理");
        String upload = port.initUpload("media", "tmp/parts.bin");
        port.uploadPart(upload, 1, "P1");
        port.uploadPart(upload, 2, "P2");
        assertNotNull(port.completeUpload("media", upload));
        PresignedUrls.Ticket url = port.presign("media", "docs/y", 3);
        port.verifyPresign(url, "GET");
        ObjectListing.Result listing = port.list("media", null, "/", 10);
        assertEquals(List.of("docs/", "tmp/"), listing.commonPrefixes());
        assertTrue(listing.keys().isEmpty());
        assertEquals(List.of("method", "path", "query", "headers"), port.signatureShape(), "signkernel 签名请求形状只读联动");
        assertThrows(IllegalArgumentException.class, () -> port.setTick(1), "虚拟时钟不可回拨");
    }
}
