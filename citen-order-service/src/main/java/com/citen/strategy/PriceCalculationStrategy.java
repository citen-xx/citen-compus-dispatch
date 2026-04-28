package com.citen.strategy;

import com.citen.entity.Voucher;

/**
 * 价格计算策略抽象。
 * 不同券种只关心自己的价格算法，调用方只依赖统一接口。
 */
public interface PriceCalculationStrategy {

    /**
     * 计算订单最终实付金额，单位：分。
     *
     * @param voucher 券信息
     * @return 最终支付金额
     */
    Long calculatePrice(Voucher voucher);
}
