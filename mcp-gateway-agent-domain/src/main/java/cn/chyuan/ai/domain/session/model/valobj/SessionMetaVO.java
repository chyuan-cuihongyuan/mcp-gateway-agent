package cn.chyuan.ai.domain.session.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaVO {

    private String sessionId;

    private String gatewayId;

    private String apiKeyHash;

    private Long createTime;

    private Long lastAccessedTime;

    private String status;
}
