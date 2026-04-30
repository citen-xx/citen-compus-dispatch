package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.dto.Result;
import com.citen.entity.Resource;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.VoucherMapper;
import com.citen.service.ISeckillVoucherService;
import com.citen.service.IVoucherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.citen.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Resource> implements IVoucherService {

    @javax.annotation.Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long labId) {
        List<Resource> resources = getBaseMapper().queryVoucherOfShop(labId);
        return Result.ok(resources);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Resource resource) {
        save(resource);

        ResourceQuota resourceQuota = new ResourceQuota();
        resourceQuota.setResourceId(resource.getId());
        resourceQuota.setQuota(resource.getQuota());
        resourceQuota.setBeginTime(resource.getBeginTime());
        resourceQuota.setEndTime(resource.getEndTime());
        seckillVoucherService.save(resourceQuota);

        stringRedisTemplate.opsForValue().set(
                SECKILL_STOCK_KEY + resource.getId(),
                resource.getQuota().toString()
        );
    }
}
