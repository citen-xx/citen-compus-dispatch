package com.citen.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citen.dto.Result;
import com.citen.entity.Lab;
import com.citen.service.ILabService;
import com.citen.utils.SystemConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/lab")
public class LabController {

    @Resource
    private ILabService labService;

    @GetMapping("/{id}")
    public Result queryLabById(@PathVariable("id") Long id) {
        return labService.queryById(id);
    }

    @PostMapping
    public Result saveLab(@RequestBody Lab lab) {
        labService.save(lab);
        return Result.ok(lab.getId());
    }

    @PutMapping
    public Result updateLab(@RequestBody Lab lab) {
        return labService.updateLab(lab);
    }

    @GetMapping("/of/type")
    public Result queryLabByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return labService.queryLabByType(typeId, current, x, y);
    }

    @GetMapping("/of/name")
    public Result queryLabByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        Page<Lab> page = labService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @GetMapping("/geo/load")
    public Result loadLabData() {
        labService.loadLabData();
        return Result.ok("加载成功");
    }
}
