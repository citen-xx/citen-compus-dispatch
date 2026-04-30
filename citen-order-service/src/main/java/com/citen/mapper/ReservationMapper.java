package com.citen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citen.entity.Reservation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReservationMapper extends BaseMapper<Reservation> {

    List<Reservation> selectAdminReservationPage(@Param("offset") Long offset, @Param("pageSize") Long pageSize);
}
