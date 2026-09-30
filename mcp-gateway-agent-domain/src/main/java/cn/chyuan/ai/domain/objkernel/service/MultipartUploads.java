package cn.chyuan.ai.domain.objkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片上传（工单 1131 EW5，minio 思想）。
 * init 得 uploadId/分片按序上传编号从 1 连续校验/complete 合并出 ETag/abort 清理/未知 uploadId 拒绝。
 */
public final class MultipartUploads {

    /** 上传会话：目标 key + 已收分片（编号→内容） */
    private static final class Session {
        final String key;
        final Map<Integer, String> parts = new LinkedHashMap<>();
        boolean finished;

        Session(String key) {
            this.key = key;
        }
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private long idCounter;

    /** 初始化：空 key 拒绝；返回 uploadId */
    public String init(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("分片目标 key 不能为空");
        }
        String uploadId = "upload-" + ++idCounter;
        sessions.put(uploadId, new Session(key));
        return uploadId;
    }

    /** 上传分片：编号必须等于已收数+1（从 1 连续）；未知 uploadId/已完成会话拒绝 */
    public void uploadPart(String uploadId, int partNumber, String content) {
        Session session = require(uploadId);
        if (session.finished) {
            throw new IllegalStateException("上传已完成: " + uploadId);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("分片内容不能为空");
        }
        if (partNumber != session.parts.size() + 1) {
            throw new IllegalStateException("分片编号须连续: 期望 "
                    + (session.parts.size() + 1) + " 实际 " + partNumber);
        }
        session.parts.put(partNumber, content);
    }

    /** 合并结果：目标 key + 串接内容 */
    public record Merged(String key, String content) {
    }

    /** 完成：合并内容；编号未从 1 收满拒绝；会话终结 */
    public Merged complete(String uploadId) {
        Session session = require(uploadId);
        if (session.finished) {
            throw new IllegalStateException("重复合并: " + uploadId);
        }
        for (int i = 1; i <= session.parts.size(); i++) {
            if (!session.parts.containsKey(i)) {
                throw new IllegalStateException("缺分片: " + i);
            }
        }
        StringBuilder merged = new StringBuilder();
        for (int i = 1; i <= session.parts.size(); i++) {
            merged.append(session.parts.get(i));
        }
        session.finished = true;
        sessions.remove(uploadId);
        return new Merged(session.key, merged.toString());
    }

    /** 中止：清理分片；未知 uploadId 拒绝 */
    public void abort(String uploadId) {
        require(uploadId);
        sessions.remove(uploadId);
    }

    private Session require(String uploadId) {
        Session session = sessions.get(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("未知 uploadId: " + uploadId);
        }
        return session;
    }

    public int activeCount() {
        return sessions.size();
    }
}
