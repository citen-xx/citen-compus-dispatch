package com.citen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citen.entity.Reservation;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;

public interface ReservationMapper extends BaseMapper<Reservation> {

    List<Reservation> selectAdminReservationPage(@Param("offset") Long offset, @Param("pageSize") Long pageSize);

    Long lockResourceForReservation(@Param("resourceId") Long resourceId);

    List<Reservation> selectActiveOverlappingReservations(@Param("resourceId") Long resourceId,
                                                          @Param("reservationDate") LocalDate reservationDate,
                                                          @Param("startTime") LocalTime startTime,
                                                          @Param("endTime") LocalTime endTime);
}
