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
 * 实训室/算力中心
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_lab")
public class Lab implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 实训室/算力中心名称
     */
    private String name;

    /**
     * 资源类型 ID
     */
    private Long labTypeId;

    /**
     * 图片
     */
    private String images;

    /**
     * 所属园区/校区区域
     */
    private String area;

    /**
     * 位置描述
     */
    private String address;

    /**
     * 经度
     */
    private Double x;

    /**
     * 纬度
     */
    private Double y;

    /**
     * 单位资源使用成本
     */
    private Long avgPrice;

    /**
     * 已预约次数
     */
    private Integer sold;

    /**
     * 使用记录数
     */
    private Integer comments;

    /**
     * 资源评分
     */
    private Integer score;

    /**
     * 开放时间
     */
    private String openHours;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Double distance;
}
