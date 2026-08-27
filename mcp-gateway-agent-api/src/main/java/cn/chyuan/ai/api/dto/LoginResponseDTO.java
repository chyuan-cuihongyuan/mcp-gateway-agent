package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * admin 登录响应（工单 0017：JWT + 角色）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseDTO implements Serializable {

    private String token;

    private String username;

    /** ADMIN / READONLY */
    private String role;
}
