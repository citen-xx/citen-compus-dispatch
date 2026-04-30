package com.citen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 稀缺资源预约额度
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_resource_quota")
public class ResourceQuota implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联资源 ID
     */
    @TableId(value = "resource_id", type = IdType.INPUT)
    private Long resourceId;

    /**
     * 可预约额度
     */
    private Integer quota;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 预约开始时间
     */
    private LocalDateTime beginTime;

    /**
     * 预约结束时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
