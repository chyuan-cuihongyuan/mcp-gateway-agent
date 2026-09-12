package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.generation.service.AnnotationReplyService;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationQa;
import cn.chyuan.ai.infrastructure.dao.IAnnotationQaDao;
import cn.chyuan.ai.infrastructure.dao.po.McpAnnotationQaPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 标注回复仓储实现（工单 0202 AA7）：实现 {@link AnnotationReplyService.AnnotationStore} 端口，
 * 经 MyBatis 落 mcp_annotation_qa 表（双方言公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class AnnotationQaRepository implements AnnotationReplyService.AnnotationStore {

    @Resource
    private IAnnotationQaDao dao;

    @Override
    public List<AnnotationQa> listEnabled() {
        return dao.queryEnabled().stream().map(AnnotationQaRepository::toDomain).toList();
    }

    @Override
    public void insert(AnnotationQa qa) {
        dao.insert(toPo(qa));
    }

    @Override
    public void update(AnnotationQa qa) {
        dao.update(toPo(qa));
    }

    @Override
    public void delete(Long id) {
        dao.delete(id);
    }

    @Override
    public AnnotationQa findById(Long id) {
        return toDomain(dao.query(id));
    }

    @Override
    public void incrementHit(Long id) {
        dao.incrementHit(id);
    }

    @Override
    public AnnotationQa findByKey(String questionKey) {
        return toDomain(dao.queryByKey(questionKey));
    }

    private static McpAnnotationQaPO toPo(AnnotationQa qa) {
        McpAnnotationQaPO po = new McpAnnotationQaPO();
        po.setId(qa.id());
        po.setQuestionKey(AnnotationReplyService.normalizeKey(qa.question()));
        po.setQuestion(qa.question());
        po.setAnswer(qa.answer());
        po.setHitCount(qa.hitCount());
        po.setEnabled(qa.enabled() ? 1 : 0);
        po.setOperator(qa.operator());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    private static AnnotationQa toDomain(McpAnnotationQaPO po) {
        if (po == null) {
            return null;
        }
        return new AnnotationQa(po.getId(), po.getQuestion(), po.getAnswer(),
                po.getHitCount() == null ? 0 : po.getHitCount(),
                po.getEnabled() != null && po.getEnabled() == 1, po.getOperator());
    }
}
