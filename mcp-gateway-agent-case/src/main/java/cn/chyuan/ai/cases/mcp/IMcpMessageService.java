package cn.chyuan.ai.cases.mcp;

import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import org.springframework.http.ResponseEntity;

/**
 *
 * @author chyuan
 *         2025/12/13 09:08
 */
public interface IMcpMessageService {

    ResponseEntity<Void> handleMessage(HandleMessageCommandEntity commandEntity) throws Exception;

}
