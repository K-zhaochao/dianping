package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author K-zhaochao
 * @since 2026-06-04
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 获取商户类型信息
     * @return
     */
    Result queryShopTypeList();
}
