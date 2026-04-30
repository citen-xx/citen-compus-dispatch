package com.citen.mapper;

import com.citen.entity.Reservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<Reservation> {

    List<Reservation> selectAdminPageByDeferredJoin(@Param("offset") Long offset, @Param("pageSize") Long pageSize);
}
