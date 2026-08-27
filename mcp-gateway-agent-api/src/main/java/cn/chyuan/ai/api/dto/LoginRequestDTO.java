package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * admin 登录请求（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequestDTO implements Serializable {

    private String username;

    private String password;
}
