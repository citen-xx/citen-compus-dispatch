package com.citen.controller;

import com.citen.dto.Result;
import com.citen.service.ILabTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/lab-type")
public class LabTypeController {

    @Resource
    private ILabTypeService labTypeService;

    @GetMapping("/list")
    public Result queryLabTypeList() {
        return labTypeService.queryAll();
    }
}
