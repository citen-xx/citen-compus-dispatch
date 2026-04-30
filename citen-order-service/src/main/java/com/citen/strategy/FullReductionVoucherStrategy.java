package com.citen.strategy;

import com.citen.entity.Resource;
import org.springframework.stereotype.Component;

/**
 * 满减券策略：
 * 约定 reserveValue 表示满减前金额，confirmValue 表示减免金额。
 * 为避免出现负数，这里做一次下限保护。
 */
@Component
public class FullReductionVoucherStrategy implements PriceCalculationStrategy {

    @Override
    public Long calculatePrice(Resource voucher) {
        long reserveValue = voucher.getReserveValue() == null ? 0L : voucher.getReserveValue();
        long reductionValue = voucher.getConfirmValue() == null ? 0L : voucher.getConfirmValue();
        return Math.max(reserveValue - reductionValue, 0L);
    }
}
