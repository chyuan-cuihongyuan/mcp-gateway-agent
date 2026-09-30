package cn.chyuan.ai.domain.objkernel.service;

import java.util.List;

/**
 * 对象存储端口（工单 1134 EW8，minio 思想）。
 * bucket·put·get·presign 入口统一编排：bucket 规则·对象 ETag·版本化·生命周期·
 * 分片上传·presigned·列举折叠组合管线/signkernel 签名请求形状只读联动
 * （形状键与 signkernel canonical 参数形态对齐，不 import signkernel）/
 * obj-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ObjPort {

    void createBucket(String name);

    void enableVersioning(String name);

    void deleteBucket(String name);

    List<String> buckets();

    /** PUT：返回 ETag（EW2） */
    String put(String bucket, String key, String content);

    /** GET 最新（EW2） */
    String get(String bucket, String key);

    /** 按版本 id 检索（EW3） */
    String getAt(String bucket, String key, long versionId);

    /** DELETE：开桶写删除标记，未开桶物理删（EW3） */
    void deleteObject(String bucket, String key);

    /** 生命周期规则（EW4） */
    void rule(String prefix, int expireDays);

    /** 到期清理：返回本次清理 key（EW4） */
    List<String> sweep();

    String initUpload(String bucket, String key);

    void uploadPart(String uploadId, int partNumber, String content);

    /** 完成分片合并入库：返回合并 ETag（EW5） */
    String completeUpload(String bucket, String uploadId);

    void abortUpload(String uploadId);

    /** 预签：expireAfterTicks 为相对有效期（EW6） */
    PresignedUrls.Ticket presign(String bucket, String key, long expireAfterTicks);

    void verifyPresign(PresignedUrls.Ticket ticket, String method);

    ObjectListing.Result list(String bucket, String prefix, String delimiter, int maxKeys);

    /** 虚拟时钟（EW4/EW6 用） */
    void setTick(long tick);

    long tick();

    /** signkernel 签名请求形状只读联动（canonical: method/path/query/headers） */
    List<String> signatureShape();

    static ObjPort inMemory() {
        return new ObjectStore();
    }
}
