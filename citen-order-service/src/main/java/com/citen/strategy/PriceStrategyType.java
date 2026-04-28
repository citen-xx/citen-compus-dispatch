package com.citen.strategy;

/**
 * 价格策略类型常量。
 * 这里故意单独抽出来，避免服务层到处出现魔法值。
 */
public final class PriceStrategyType {

    private PriceStrategyType() {
    }

    /**
     * 原价。
     */
    public static final int NORMAL = 0;

    /**
     * 打折券。
     */
    public static final int DISCOUNT = 1;

    /**
     * 满减券。
     */
    public static final int FULL_REDUCTION = 2;
}
