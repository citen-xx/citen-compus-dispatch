package com.citen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citen.dto.Result;
import com.citen.entity.Lab;

public interface ILabService extends IService<Lab> {

    Result queryById(Long id);

    Result updateLab(Lab lab);

    Result queryLabByType(Integer labTypeId, Integer current, Double x, Double y);

    void loadLabData();
}
