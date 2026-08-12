package com.citen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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
     * 预约日期
     */
    private LocalDate reservationDate;

    /**
     * 当天的预约开始时间
     */
    private LocalTime startTime;

    /**
     * 当天的预约结束时间
     */
    private LocalTime endTime;

    /**
     * 待确认状态的过期时间
     */
    private LocalDateTime expireAt;

    /**
     * RabbitMQ 超时消息是否已收到发布确认
     */
    private Boolean timeoutMessageSent;

    /**
     * 预约方式
     */
    private Integer reserveType;

    /**
     * 预约状态：
     * 1-待确认
     * 2-已确认
     * 3-已完成
     * 4-已取消
     * 5-超时违约
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 确认时间
     */
    private LocalDateTime confirmTime;

    /**
     * 完成时间
     */
    private LocalDateTime completeTime;

    /**
     * 取消时间
     */
    private LocalDateTime cancelTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
