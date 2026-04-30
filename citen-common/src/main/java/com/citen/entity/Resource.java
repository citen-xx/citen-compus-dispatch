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
 * 具体资源
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_resource")
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属实训室/算力中心 ID
     */
    private Long labId;

    /**
     * 资源名称
     */
    private String name;

    /**
     * 资源描述
     */
    private String description;

    /**
     * 使用规则
     */
    private String usageRules;

    /**
     * 预约占用值
     */
    private Long reserveValue;

    /**
     * 签到确认值
     */
    private Long confirmValue;

    /**
     * 资源模式
     */
    private Integer resourceMode;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 可预约额度
     */
    @TableField(exist = false)
    private Integer quota;

    /**
     * 开放开始时间
     */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 开放结束时间
     */
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
