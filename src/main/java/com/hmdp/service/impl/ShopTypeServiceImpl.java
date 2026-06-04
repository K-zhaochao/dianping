package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author K-zhaochao
 * @since 2026-06-04
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 获取商户类型信息
     * @return
     */
    public Result queryShopTypeList() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        // 1.从Redis获取商户类型信息
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);

        // 2.若存在，则直接返回
        if (shopTypeListJson != null && !shopTypeListJson.isEmpty()) {
            try {
                List<ShopType> shopTypeList = objectMapper
                        .readValue(shopTypeListJson, new TypeReference<List<ShopType>>() {});
                return Result.ok(shopTypeList);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("缓存解析失败", e);
            }
        }

        // 3.若不存在则去数据库查找
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 4.若数据库不存在则返回错误
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.fail("商品分类不存在！");
        }

        // 5.将商户类型信息存入Redis
        try {
            shopTypeListJson = objectMapper.writeValueAsString(shopTypeList);
            stringRedisTemplate.opsForValue().set(key, shopTypeListJson, RedisConstants.SHOP_TYPE_TTL, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        // 6.返回商户信息
        return Result.ok(shopTypeList);
    }
}
