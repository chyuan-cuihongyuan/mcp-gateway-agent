package cn.chyuan.ai.domain.modelcatalog.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 模型目录服务（工单 0281 AJ5，借鉴 Ollama model registry/OpenRouter 模型元数据）—
 * 模型能力矩阵：context 上限/模态集合/归属计价条目/状态；与既有计价表联动校验
 * （计价条目缺失→警告不阻断）；context 超限预检接口（供请求链路调用，默认关）。
 * 存储经 {@link CatalogStore} 端口（infrastructure 落 model_catalog 表 PG V0018）。
 */
@Slf4j
@Service
public class ModelCatalogService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    private static final Set<String> LEGAL_STATUS = Set.of(STATUS_ACTIVE, STATUS_DEPRECATED);
    private static final Set<String> LEGAL_MODALITIES = Set.of("text", "vision", "audio", "embedding");

    /** 模型条目值对象 */
    public record ModelEntry(Long id, String model, int contextLimit, Set<String> modalities,
            Long pricingEntryId, String status, String note, String operator) {

        public ModelEntry {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("模型名不能为空");
            }
            if (contextLimit < 0) {
                throw new IllegalArgumentException("contextLimit 不能为负: " + contextLimit);
            }
            modalities = modalities == null || modalities.isEmpty() ? Set.of("text") : Set.copyOf(modalities);
            for (String modality : modalities) {
                if (!LEGAL_MODALITIES.contains(modality)) {
                    throw new IllegalArgumentException("非法模态: " + modality);
                }
            }
            status = status == null || status.isBlank() ? STATUS_ACTIVE : status;
            if (!LEGAL_STATUS.contains(status)) {
                throw new IllegalArgumentException("非法状态: " + status);
            }
        }

        public boolean active() {
            return STATUS_ACTIVE.equals(status);
        }
    }

    /** 计价联动校验结果（缺失=警告不阻断） */
    public record PricingLinkage(boolean linked, boolean warned) {
    }

    /** 计价条目存在性探测端口（复用既有计价表域） */
    public interface PricingEntryPort {

        boolean exists(long pricingEntryId);
    }

    /** 目录持久化端口 */
    public interface CatalogStore {

        void insert(ModelEntry entry);

        void update(ModelEntry entry);

        ModelEntry findByModel(String model);

        List<ModelEntry> listAll();
    }

    private final CatalogStore store;
    private final PricingEntryPort pricingPort;

    public ModelCatalogService(CatalogStore store, PricingEntryPort pricingPort) {
        this.store = store;
        this.pricingPort = pricingPort;
    }

    /** 注册模型（重复注册拒绝；计价条目缺失记警告） */
    public ModelEntry register(ModelEntry entry) {
        if (store.findByModel(entry.model()) != null) {
            throw new IllegalArgumentException("模型已存在: " + entry.model());
        }
        ModelEntry withId = new ModelEntry(nextId(), entry.model(), entry.contextLimit(), entry.modalities(),
                entry.pricingEntryId(), entry.status(), entry.note(), entry.operator());
        store.insert(withId);
        PricingLinkage linkage = checkPricing(withId);
        if (linkage.warned()) {
            log.warn("模型计价条目缺失（警告不阻断）: model={} pricingEntryId={}", withId.model(), withId.pricingEntryId());
        }
        log.info("模型目录注册: model={} context={} modalities={}", withId.model(), withId.contextLimit(), withId.modalities());
        return withId;
    }

    public ModelEntry update(ModelEntry entry) {
        if (entry.id() == null || store.findByModel(entry.model()) == null) {
            throw new IllegalArgumentException("模型不存在: " + entry.model());
        }
        store.update(entry);
        return entry;
    }

    public ModelEntry get(String model) {
        ModelEntry entry = store.findByModel(model);
        if (entry == null) {
            throw new IllegalArgumentException("模型不在目录: " + model);
        }
        return entry;
    }

    public List<ModelEntry> listAll() {
        return store.listAll();
    }

    /** 计价联动校验 */
    public PricingLinkage checkPricing(ModelEntry entry) {
        if (entry.pricingEntryId() == null) {
            return new PricingLinkage(false, true);
        }
        boolean exists = pricingPort != null && pricingPort.exists(entry.pricingEntryId());
        return new PricingLinkage(exists, !exists);
    }

    /** context 超限预检（请求链路可选调用）：true=通过 */
    public boolean assertContextWithinLimit(String model, int estimatedTokens) {
        ModelEntry entry = store.findByModel(model);
        if (entry == null) {
            return true;
        }
        return estimatedTokens <= entry.contextLimit();
    }

    private long sequence = 0;

    private synchronized long nextId() {
        long maxStored = store.listAll().stream().mapToLong(e -> e.id() == null ? 0 : e.id()).max().orElse(0);
        return Math.max(maxStored, sequence) + 1;
    }
}
