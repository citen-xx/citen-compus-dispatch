package com.citen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citen.entity.Resource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ResourceMapper extends BaseMapper<Resource> {

    List<Resource> queryResourcesOfLab(@Param("labId") Long labId);
}
