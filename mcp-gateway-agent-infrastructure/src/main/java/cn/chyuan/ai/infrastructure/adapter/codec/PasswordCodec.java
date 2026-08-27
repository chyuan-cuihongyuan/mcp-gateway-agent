package cn.chyuan.ai.infrastructure.adapter.codec;

import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码编解码实现（工单 0017：BCrypt）
 *
 * @author chyuan
 */
@Component
public class PasswordCodec implements IPasswordCodec {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }
}
