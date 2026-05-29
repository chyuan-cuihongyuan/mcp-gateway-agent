package cn.chyuan.ai.domain.session.adapter.repository;

import cn.chyuan.ai.domain.session.model.valobj.SessionMetaVO;

import java.time.Duration;

public interface ISessionMetaRepository {

    void save(SessionMetaVO meta, Duration ttl);

    SessionMetaVO find(String sessionId);

    void touch(String sessionId, Duration ttl);

    void delete(String sessionId);
}
