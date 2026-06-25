package com.max.service;

import com.max.dto.Result;
import com.max.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 *
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryAll();
}
