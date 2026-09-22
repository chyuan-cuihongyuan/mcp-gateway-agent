package cn.chyuan.ai.domain.vaultkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量重包裹（工单 0567 BP3，vault rewrap 思想）。
 * 不解业务明文直接换 KEK 重包裹（KeyRing.rewrap）/批量逐项处理/
 * 失败信封逐项清单（版本未注册/密文篡改）/成功项不受失败项影响。
 */
public final class RewrapService {

    /** 单项结果 */
    public record Item(int index, KeyRing.Envelope rewrapped, String error) {
        public boolean success() {
            return error == null;
        }
    }

    /** 批量结果：成功/失败分列（保持输入序） */
    public record BatchResult(List<Item> items, int successCount, int failureCount) {
    }

    private final KeyRing ring;

    public RewrapService(KeyRing ring) {
        if (ring == null) {
            throw new IllegalArgumentException("密钥环不得为 null");
        }
        this.ring = ring;
    }

    /** 单信封重包裹到当前版本 */
    public Item rewrap(KeyRing.Envelope envelope) {
        try {
            return new Item(0, ring.rewrap(envelope, ring.currentVersion()), null);
        } catch (IllegalArgumentException e) {
            return new Item(0, null, e.getMessage());
        }
    }

    /** 批量重包裹：逐项处理互不影响 */
    public BatchResult rewrapBatch(List<KeyRing.Envelope> envelopes) {
        List<Item> items = new ArrayList<>(envelopes.size());
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < envelopes.size(); i++) {
            try {
                items.add(new Item(i, ring.rewrap(envelopes.get(i), ring.currentVersion()), null));
                ok++;
            } catch (IllegalArgumentException e) {
                items.add(new Item(i, null, e.getMessage()));
                failed++;
            }
        }
        return new BatchResult(List.copyOf(items), ok, failed);
    }

    /** 重包裹往返幂等：同信封重包裹两次结果等价可解 */
    public boolean idempotent(KeyRing.Envelope envelope) {
        try {
            KeyRing.Envelope first = ring.rewrap(envelope, ring.currentVersion());
            KeyRing.Envelope second = ring.rewrap(first, ring.currentVersion());
            return java.util.Arrays.equals(
                    ring.unwrapDataKey(first), ring.unwrapDataKey(second));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
