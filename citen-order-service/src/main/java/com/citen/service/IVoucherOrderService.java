package com.citen.service;

import com.citen.dto.Result;
import com.citen.entity.Reservation;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<Reservation> {

    Result seckillVoucher(Long resourceId);

    Result queryAdminOrderPage(Long current, Long size);

    void createVoucherOrder(Reservation voucherOrder);


    void cancelTimeoutOrder(Long orderId);
}
