package com.citen.strategy;

import com.citen.entity.Resource;
import org.springframework.stereotype.Component;

/**
 * 打折券策略：
 * 当前表结构中 reserveValue 表示折后实付价，confirmValue 表示券面价值。
 * 因此这里额外兜底一次，保证折后价不会高于券面价值。
 */
@Component
public class DiscountVoucherStrategy implements PriceCalculationStrategy {

    @Override
    public Long calculatePrice(Resource voucher) {
        long reserveValue = voucher.getReserveValue() == null ? 0L : voucher.getReserveValue();
        long confirmValue = voucher.getConfirmValue() == null ? reserveValue : voucher.getConfirmValue();
        return Math.min(reserveValue, confirmValue);
    }
}
