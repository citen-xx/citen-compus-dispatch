package com.citen.controller;

import com.citen.dto.Result;
import com.citen.service.IReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/reservation")
public class ReservationController {

    @Resource
    private IReservationService reservationService;

    @PostMapping("/reserve/{id}")
    public Result reserveResource(@PathVariable("id") Long resourceId) {
        return reservationService.reserveResource(resourceId);
    }

    @PostMapping("/confirm/{id}")
    public Result confirmReservation(@PathVariable("id") Long reservationId) {
        return reservationService.confirmReservation(reservationId);
    }

    @PostMapping("/cancel/{id}")
    public Result cancelReservation(@PathVariable("id") Long reservationId) {
        return reservationService.cancelReservation(reservationId);
    }

    @GetMapping("/admin/page")
    public Result queryAdminReservationPage(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "10") Long size) {
        return reservationService.queryAdminReservationPage(current, size);
    }
}
