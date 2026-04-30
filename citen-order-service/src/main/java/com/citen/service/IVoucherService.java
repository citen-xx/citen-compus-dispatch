package com.citen.service;

import com.citen.dto.Result;
import com.citen.entity.Resource;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Resource> {

    Result queryVoucherOfShop(Long labId);

    void addSeckillVoucher(Resource voucher);
}
