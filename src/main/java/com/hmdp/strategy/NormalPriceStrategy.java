package com.hmdp.strategy;

import com.hmdp.entity.Voucher;
import org.springframework.stereotype.Component;

/**
 * 原价策略：
 * 不参与任何优惠，直接返回券配置中的支付金额。
 */
@Component
public class NormalPriceStrategy implements PriceCalculationStrategy {

    @Override
    public Long calculatePrice(Voucher voucher) {
        return voucher.getPayValue() == null ? 0L : voucher.getPayValue();
    }
}
