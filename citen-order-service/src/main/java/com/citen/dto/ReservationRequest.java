package com.citen.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public class ReservationRequest {

    @NotNull(message = "预约日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate reservationDate;

    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    public LocalDate getReservationDate() {
        return reservationDate;
    }

    public void setReservationDate(LocalDate reservationDate) {
        this.reservationDate = reservationDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }
}
