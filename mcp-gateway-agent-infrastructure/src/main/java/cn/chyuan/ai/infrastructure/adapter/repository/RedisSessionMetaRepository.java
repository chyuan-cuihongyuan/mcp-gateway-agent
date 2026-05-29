package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionMetaRepository;
import cn.chyuan.ai.domain.session.model.valobj.SessionMetaVO;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Slf4j
@Repository
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisSessionMetaRepository implements ISessionMetaRepository {

    private static final String KEY_PREFIX = "mcp:session:meta:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void save(SessionMetaVO meta, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key(meta.getSessionId()), JSON.toJSONString(meta), ttl);
        } catch (Exception e) {
            log.debug("save session meta to redis failed: sessionId={}", meta.getSessionId(), e);
        }
    }

    @Override
    public SessionMetaVO find(String sessionId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key(sessionId));
            return value == null ? null : JSON.parseObject(value, SessionMetaVO.class);
        } catch (Exception e) {
            log.debug("read session meta from redis failed: sessionId={}", sessionId, e);
            return null;
        }
    }

    @Override
    public void touch(String sessionId, Duration ttl) {
        try {
            SessionMetaVO meta = find(sessionId);
            if (meta == null) {
                return;
            }
            meta.setLastAccessedTime(System.currentTimeMillis());
            save(meta, ttl);
        } catch (Exception e) {
            log.debug("touch session meta failed: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            stringRedisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            log.debug("delete session meta failed: sessionId={}", sessionId, e);
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
