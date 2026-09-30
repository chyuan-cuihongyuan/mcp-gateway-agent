package cn.chyuan.ai.domain.containkernel.service;

/**
 * 镜像引用（工单 1167 FB1，moby 思想）。
 * name:tag 解析/tag 缺省 latest/非法名（大写/空）拒绝/digest 形式引用。
 */
public record ImageReferences(String repository, String tag, String digest) {

    public ImageReferences {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("镜像仓库名不能为空");
        }
        if (repository.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("镜像名不得含大写: " + repository);
        }
        if (digest != null && !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest 须为 sha256:64 位十六进制: " + digest);
        }
    }

    /** 解析：name / name:tag / name@sha256:... */
    public static ImageReferences parse(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("镜像引用不能为空");
        }
        int at = reference.indexOf('@');
        if (at >= 0) {
            return new ImageReferences(reference.substring(0, at), null, reference.substring(at + 1));
        }
        int colon = reference.lastIndexOf(':');
        if (colon < 0) {
            return new ImageReferences(reference, "latest", null);
        }
        String tag = reference.substring(colon + 1);
        if (tag.isBlank()) {
            throw new IllegalArgumentException("tag 不能为空: " + reference);
        }
        return new ImageReferences(reference.substring(0, colon), tag, null);
    }

    /** 入库键：digest 优先 */
    public String key() {
        return digest != null ? repository + "@" + digest : repository + ":" + tag;
    }
}
