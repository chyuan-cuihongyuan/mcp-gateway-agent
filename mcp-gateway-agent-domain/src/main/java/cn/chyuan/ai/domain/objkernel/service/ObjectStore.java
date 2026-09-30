package cn.chyuan.ai.domain.objkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象存储组合实现（工单 1134 EW8，minio 思想）。
 * 桶空间隔离：每桶独立对象命名空间；生命周期清理与预签校验走虚拟时钟。
 */
public final class ObjectStore implements ObjPort {

    static final String DEFAULT_SECRET = "obj-kernel-presign-secret";

    private final Buckets buckets = new Buckets();
    private final Map<String, Objects> spaces = new LinkedHashMap<>();
    private final LifecycleRules rules = new LifecycleRules();
    private final MultipartUploads multiparts = new MultipartUploads();
    private final PresignedUrls presigner = new PresignedUrls(DEFAULT_SECRET);
    private long tick;

    private Objects space(String bucket) {
        buckets.require(bucket);
        return spaces.computeIfAbsent(bucket, b -> new Objects());
    }

    @Override
    public void createBucket(String name) {
        buckets.create(name);
    }

    @Override
    public void enableVersioning(String name) {
        buckets.enableVersioning(name);
    }

    @Override
    public void deleteBucket(String name) {
        Objects space = spaces.get(name);
        buckets.delete(name, space == null || space.isEmpty());
        if (space != null && space.isEmpty()) {
            spaces.remove(name);
        }
    }

    @Override
    public List<String> buckets() {
        return buckets.list();
    }

    @Override
    public String put(String bucket, String key, String content) {
        return space(bucket).put(key, content, tick, buckets.versioned(bucket)).etag();
    }

    @Override
    public String get(String bucket, String key) {
        return space(bucket).get(key).content();
    }

    @Override
    public String getAt(String bucket, String key, long versionId) {
        return space(bucket).getAt(key, versionId).content();
    }

    @Override
    public void deleteObject(String bucket, String key) {
        space(bucket).delete(key, tick, buckets.versioned(bucket));
    }

    @Override
    public void rule(String prefix, int expireDays) {
        rules.add(prefix, expireDays);
    }

    @Override
    public List<String> sweep() {
        List<String> swept = new ArrayList<>();
        for (Objects space : spaces.values()) {
            for (String key : space.allKeys()) {
                Objects.ObjectVersion latest = space.versionsOf(key).latest();
                if (!latest.deleteMarker() && rules.expired(key, latest.modifiedTick(), tick)) {
                    space.remove(key);
                    swept.add(key);
                }
            }
        }
        return List.copyOf(swept);
    }

    @Override
    public String initUpload(String bucket, String key) {
        space(bucket);
        return multiparts.init(key);
    }

    @Override
    public void uploadPart(String uploadId, int partNumber, String content) {
        multiparts.uploadPart(uploadId, partNumber, content);
    }

    @Override
    public String completeUpload(String bucket, String uploadId) {
        MultipartUploads.Merged merged = multiparts.complete(uploadId);
        return space(bucket).put(merged.key(), merged.content(), tick, buckets.versioned(bucket)).etag();
    }

    @Override
    public void abortUpload(String uploadId) {
        multiparts.abort(uploadId);
    }

    @Override
    public PresignedUrls.Ticket presign(String bucket, String key, long expireAfterTicks) {
        buckets.require(bucket);
        return presigner.sign("GET", bucket, key, tick, tick + expireAfterTicks);
    }

    @Override
    public void verifyPresign(PresignedUrls.Ticket ticket, String method) {
        presigner.verify(ticket, method, tick);
    }

    @Override
    public ObjectListing.Result list(String bucket, String prefix, String delimiter, int maxKeys) {
        return ObjectListing.list(space(bucket).liveKeys(), prefix, delimiter, maxKeys);
    }

    @Override
    public void setTick(long tick) {
        if (tick < this.tick) {
            throw new IllegalArgumentException("虚拟时钟不可回拨: " + tick);
        }
        this.tick = tick;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public List<String> signatureShape() {
        return List.of("method", "path", "query", "headers");
    }
}
