package com.citen.strategy;

import com.citen.entity.Resource;

/**
 * 资源额度分配策略接口。
 */
public interface ResourceAllocationStrategy {

    /**
     * 计算本次预约需要占用的算力点数/资源额度。
     */
    Long calculateRequiredQuota(Resource resource);
}
