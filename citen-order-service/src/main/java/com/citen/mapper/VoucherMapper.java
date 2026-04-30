package com.citen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citen.entity.Resource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Resource> {

    List<Resource> queryVoucherOfShop(@Param("labId") Long labId);
}
