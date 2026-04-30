package com.citen.strategy;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 资源额度分配策略工厂。
 */
@Component
public class ResourceAllocationStrategyFactory {

    private final Map<Integer, ResourceAllocationStrategy> strategyMap = new HashMap<>();
    private final ResourceAllocationStrategy defaultStrategy;

    public ResourceAllocationStrategyFactory(ComputePointStrategy computePointStrategy) {
        this.defaultStrategy = computePointStrategy;
        strategyMap.put(ResourceAllocationStrategyType.COMPUTE_POINT, computePointStrategy);
    }

    public ResourceAllocationStrategy getStrategy(Integer strategyType) {
        return strategyMap.getOrDefault(strategyType, defaultStrategy);
    }
}
