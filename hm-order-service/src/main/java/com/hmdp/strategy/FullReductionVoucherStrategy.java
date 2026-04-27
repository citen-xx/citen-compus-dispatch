package com.hmdp.strategy;

import com.hmdp.entity.Voucher;
import org.springframework.stereotype.Component;

/**
 * 满减券策略：
 * 约定 payValue 表示满减前金额，actualValue 表示减免金额。
 * 为避免出现负数，这里做一次下限保护。
 */
@Component
public class FullReductionVoucherStrategy implements PriceCalculationStrategy {

    @Override
    public Long calculatePrice(Voucher voucher) {
        long payValue = voucher.getPayValue() == null ? 0L : voucher.getPayValue();
        long reductionValue = voucher.getActualValue() == null ? 0L : voucher.getActualValue();
        return Math.max(payValue - reductionValue, 0L);
    }
}
