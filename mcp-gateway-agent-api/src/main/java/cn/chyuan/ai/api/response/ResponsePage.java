package cn.chyuan.ai.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分页返回对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponsePage<T> implements Serializable {

    private String code;
    private String info;
    private T data;

    /**
     * 总记录数
     */
    private Long total;

    /** 成功分页响应（code=0000） */
    public static <T> ResponsePage<T> success(T data, Long total) {
        return ResponsePage.<T>builder().code("0000").info("成功").data(data).total(total).build();
    }

}
