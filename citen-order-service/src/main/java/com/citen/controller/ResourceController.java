package com.citen.controller;

import com.citen.dto.Result;
import com.citen.entity.Resource;
import com.citen.service.IResourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/resource")
public class ResourceController {

    @javax.annotation.Resource
    private IResourceService resourceService;

    @PostMapping
    public Result addResource(@RequestBody Resource resource) {
        resourceService.save(resource);
        return Result.ok(resource.getId());
    }

    @PostMapping("/quota")
    public Result addResourceQuota(@RequestBody Resource resource) {
        resourceService.addResourceQuota(resource);
        return Result.ok(resource.getId());
    }

    @GetMapping("/list/{labId}")
    public Result queryResourceOfLab(@PathVariable("labId") Long labId) {
        return resourceService.queryResourcesOfLab(labId);
    }
}
