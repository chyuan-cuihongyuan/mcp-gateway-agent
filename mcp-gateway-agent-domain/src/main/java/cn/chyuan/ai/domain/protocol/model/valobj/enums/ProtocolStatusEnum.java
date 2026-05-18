package cn.chyuan.ai.domain.protocol.model.valobj.enums;

import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 协议状态枚举
 *
 * @author chyuan
 *         2026/3/13 08:40
 */
@Getter
@AllArgsConstructor
public enum ProtocolStatusEnum {

    ENABLE(1, "启用"),
    DISABLE(0, "禁用")

    ;

    private Integer code;
    private String info;

    public static ProtocolStatusEnum getByCode(Integer code) {
        if (null == code) {
            return null;
        }
        for (ProtocolStatusEnum anEnum : ProtocolStatusEnum.values()) {
            if (anEnum.getCode().equals(code)) {
                return anEnum;
            }
        }

        throw new AppException(ResponseCode.ENUM_NOT_FOUND.getCode(), ResponseCode.ENUM_NOT_FOUND.getInfo());
    }

}
