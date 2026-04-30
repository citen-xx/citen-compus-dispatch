package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.dto.Result;
import com.citen.entity.LabType;
import com.citen.mapper.LabTypeMapper;
import com.citen.service.ILabTypeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LabTypeServiceImpl extends ServiceImpl<LabTypeMapper, LabType> implements ILabTypeService {

    @Override
    public Result queryAll() {
        List<LabType> labTypes = query().orderByAsc("sort").list();
        if (labTypes == null || labTypes.isEmpty()) {
            return Result.fail("资源类型不存在");
        }
        return Result.ok(labTypes);
    }
}
