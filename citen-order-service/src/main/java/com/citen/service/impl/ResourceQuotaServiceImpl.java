package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.ResourceQuotaMapper;
import com.citen.service.IResourceQuotaService;
import org.springframework.stereotype.Service;

@Service
public class ResourceQuotaServiceImpl extends ServiceImpl<ResourceQuotaMapper, ResourceQuota> implements IResourceQuotaService {
}
