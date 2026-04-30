package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.dto.Result;
import com.citen.entity.Resource;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.ResourceMapper;
import com.citen.service.IResourceQuotaService;
import com.citen.service.IResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.citen.utils.RedisConstants.RESOURCE_QUOTA_KEY;

@Service
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements IResourceService {

    @javax.annotation.Resource
    private IResourceQuotaService resourceQuotaService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryResourcesOfLab(Long labId) {
        List<Resource> resources = getBaseMapper().queryResourcesOfLab(labId);
        return Result.ok(resources);
    }

    @Override
    @Transactional
    public void addResourceQuota(Resource resource) {
        save(resource);

        ResourceQuota resourceQuota = new ResourceQuota();
        resourceQuota.setResourceId(resource.getId());
        resourceQuota.setQuota(resource.getQuota());
        resourceQuota.setBeginTime(resource.getBeginTime());
        resourceQuota.setEndTime(resource.getEndTime());
        resourceQuotaService.save(resourceQuota);

        stringRedisTemplate.opsForValue().set(
                RESOURCE_QUOTA_KEY + resource.getId(),
                resource.getQuota().toString()
        );
    }
}
