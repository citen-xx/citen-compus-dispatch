package com.citen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("tb_reservation_compensation")
public class ReservationCompensation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reservationId;
    private Long userId;
    private Long resourceId;
    private LocalDate reservationDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String compensationType;
    private Integer status;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
