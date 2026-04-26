package com.hmdp.strategy;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 简单工厂：
 * 负责根据券类型分发对应的价格策略。
 *
 * OCP 说明：
 * 服务层只依赖工厂和策略接口，不依赖任何具体实现。
 * 未来若新增 N 折券、会员券等算法，只需新增策略类并在工厂注册，
 * VoucherOrderServiceImpl 无需修改核心下单流程。
 */
@Component
public class PriceStrategyFactory {

    private final Map<Integer, PriceCalculationStrategy> strategyMap = new HashMap<>();
    private final PriceCalculationStrategy defaultStrategy;

    public PriceStrategyFactory(NormalPriceStrategy normalPriceStrategy,
                                DiscountVoucherStrategy discountVoucherStrategy,
                                FullReductionVoucherStrategy fullReductionVoucherStrategy) {
        this.defaultStrategy = normalPriceStrategy;
        strategyMap.put(PriceStrategyType.NORMAL, normalPriceStrategy);
        strategyMap.put(PriceStrategyType.DISCOUNT, discountVoucherStrategy);
        strategyMap.put(PriceStrategyType.FULL_REDUCTION, fullReductionVoucherStrategy);
    }

    public PriceCalculationStrategy getStrategy(Integer voucherType) {
        return strategyMap.getOrDefault(voucherType, defaultStrategy);
    }
}
