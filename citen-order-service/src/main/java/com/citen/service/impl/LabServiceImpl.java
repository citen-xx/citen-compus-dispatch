package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.dto.Result;
import com.citen.entity.Lab;
import com.citen.mapper.LabMapper;
import com.citen.service.ILabService;
import com.citen.utils.SystemConstants;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LabServiceImpl extends ServiceImpl<LabMapper, Lab> implements ILabService {

    private static final String LAB_GEO_KEY_PREFIX = "lab:geo:";

    @javax.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Lab lab = getById(id);
        if (lab == null) {
            return Result.fail("实训室/算力中心不存在");
        }
        return Result.ok(lab);
    }

    @Override
    public Result updateLab(Lab lab) {
        if (lab.getId() == null) {
            return Result.fail("实训室/算力中心ID不能为空");
        }
        updateById(lab);
        return Result.ok();
    }

    @Override
    public Result queryLabByType(Integer labTypeId, Integer current, Double x, Double y) {
        Page<Lab> page = query()
                .eq(labTypeId != null, "lab_type_id", labTypeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public void loadLabData() {
        List<Lab> labs = list();
        Map<Long, List<Lab>> grouped = labs.stream().collect(Collectors.groupingBy(Lab::getLabTypeId));
        for (Map.Entry<Long, List<Lab>> entry : grouped.entrySet()) {
            String key = LAB_GEO_KEY_PREFIX + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(entry.getValue().size());
            for (Lab lab : entry.getValue()) {
                if (lab.getX() == null || lab.getY() == null) {
                    continue;
                }
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        lab.getId().toString(),
                        new Point(lab.getX(), lab.getY())
                ));
            }
            if (!locations.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, locations);
            }
        }
    }
}
