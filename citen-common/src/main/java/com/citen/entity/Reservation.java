package com.citen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预约记录
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation")
public class Reservation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 预约用户 ID
     */
    private Long userId;

    /**
     * 预约资源 ID
     */
    private Long resourceId;

    /**
     * 预约方式
     */
    private Integer reserveType;

    /**
     * 预约状态
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 签到确认时间
     */
    private LocalDateTime confirmTime;

    /**
     * 预约完成时间
     */
    private LocalDateTime completeTime;

    /**
     * 预约取消时间
     */
    private LocalDateTime cancelTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 运行期计算得到的实际分配额度，不落库
     */
    @TableField(exist = false)
    private Long allocatedQuota;
}
