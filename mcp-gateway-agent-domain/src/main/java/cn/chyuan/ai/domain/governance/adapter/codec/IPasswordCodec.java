package cn.chyuan.ai.domain.governance.adapter.codec;

/**
 * 密码编解码端口（工单 0017；infrastructure 以 spring-security-crypto BCrypt 落地）
 *
 * @author chyuan
 */
public interface IPasswordCodec {

    String encode(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
