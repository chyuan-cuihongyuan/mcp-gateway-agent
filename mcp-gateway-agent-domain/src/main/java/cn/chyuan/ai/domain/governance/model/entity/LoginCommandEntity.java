package cn.chyuan.ai.domain.governance.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * admin 登录指令实体（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginCommandEntity {

    private String username;

    private String password;
}
