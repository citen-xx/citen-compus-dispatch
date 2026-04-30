package com.citen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citen.dto.Result;
import com.citen.entity.Resource;

public interface IResourceService extends IService<Resource> {

    Result queryResourcesOfLab(Long labId);

    void addResourceQuota(Resource resource);
}
