package com.citen.strategy;

import com.citen.entity.Voucher;
import org.springframework.stereotype.Component;

/**
 * 打折券策略：
 * 当前表结构中 payValue 表示折后实付价，actualValue 表示券面价值。
 * 因此这里额外兜底一次，保证折后价不会高于券面价值。
 */
@Component
public class DiscountVoucherStrategy implements PriceCalculationStrategy {

    @Override
    public Long calculatePrice(Voucher voucher) {
        long payValue = voucher.getPayValue() == null ? 0L : voucher.getPayValue();
        long actualValue = voucher.getActualValue() == null ? payValue : voucher.getActualValue();
        return Math.min(payValue, actualValue);
    }
}
