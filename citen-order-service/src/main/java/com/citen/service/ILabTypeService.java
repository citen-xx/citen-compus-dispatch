package com.citen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citen.dto.Result;
import com.citen.entity.LabType;

public interface ILabTypeService extends IService<LabType> {

    Result queryAll();
}
