package cn.chyuan.ai.domain.protocol.model.entity;

import cn.chyuan.ai.domain.protocol.model.valobj.http.HTTPProtocolVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 存储协议实体
 *
 * @author chyuan
 *         2026/3/13 07:38
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageCommandEntity {

    /**
     * 协议列表数据
     */
    private List<HTTPProtocolVO> httpProtocolVOS;

}
