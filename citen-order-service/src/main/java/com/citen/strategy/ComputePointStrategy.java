package com.citen.strategy;

import com.citen.entity.Resource;
import org.springframework.stereotype.Component;

/**
 * 算力点数扣减策略。
 *
 * 当前校园资源调度场景下，统一按资源本身配置的 `reserveValue`
 * 作为本次预约需要占用的算力点数或座位额度。
 */
@Component
public class ComputePointStrategy implements ResourceAllocationStrategy {

    @Override
    public Long calculateRequiredQuota(Resource resource) {
        return resource.getReserveValue() == null ? 0L : resource.getReserveValue();
    }
}
