package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 防止缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期的方式解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        // 若返回的数据为空，则返回报错信息
        if (shop == null) {
            return Result.fail("店铺信息不存在！");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期的方式解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回null
            return null;
        }

        // 4.若存在, 需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 由于RedisData类的data属性用的是Object类，因此反序列化回来的也是一个JSON对象
        Shop shop = JSONUtil.toBean(((JSONObject) redisData.getData()), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回
            return shop;
        }

        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        if (isLock) {
            // 6.2.获取成功，开启独立线程，实现缓存重建，并返回店铺信息
            // 注意：获取锁成功应该再次检测Redis缓存是否过期，做DoubleCheck，如果存在则无需重建缓存
            // 因为你现在的锁可能是其他线程做完了缓存重建之后释放的
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            // 由于RedisData类的data属性用的是Object类，因此反序列化回来的也是一个JSON对象
            shop = JSONUtil.toBean(((JSONObject) redisData.getData()), Shop.class);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 5.1.未过期，直接返回
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    saveshop2Redis(id, RedisConstants.LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    // 注意：此处的锁是Redis实现的锁
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }

        // 返回店铺信息
        return shop;
    }

    /**
     * 对数据进行Redis预热
     * @param id
     * @param expireSeconds
     */
    public void saveshop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断命中是否是空值
        // 前面已经判断了shopJson是否存在, 既然不存在那么shopJson必定
        // 是null, 如果不是null就表示该数据是"", 防穿透的数据
        if (shopJson != null) {
            return null;
        }

        // 4.实现缓存重建
        // 4.1.尝试获取互斥锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);

            // 4.2.若获取锁失败，则休息一段时间
            if (!isLock) {
                while (true) {
                    Thread.sleep(50);
                    // 1.从redis查询商铺缓存
                    shopJson = stringRedisTemplate.opsForValue().get(key);
                    // 2.判断是否存在
                    if (StrUtil.isNotBlank(shopJson)) {
                        // 3.存在，直接返回
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    // 判断命中是否是空值
                    // 前面已经判断了shopJson是否存在, 既然不存在那么shopJson必定
                    // 是null, 如果不是null就表示该数据是"", 防穿透的数据
                    if (shopJson != null) {
                        return null;
                    }
                }
            }

            // 4.3.若获取锁成功则进行SQL查询
            Thread.sleep(200);
            shop = getById(id);

            if (shop == null) {
                // 5.查询不存在，缓存穿透保障
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.关闭互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }

        // 8.返回数据
        return shop;
    }

    /**
     * 防止缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断命中是否是空值
        // 前面已经判断了shopJson是否存在, 既然不存在那么shopJson必定
        // 是null, 如果不是null就表示该数据是"", 防穿透的数据
        if (shopJson != null) {
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        if (shop == null) {
            // 5.不存在，返回错误
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }

    /**
     * 用于查询锁是否开启
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 不能直接返回flag，因为进行拆箱可能返回空指针
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 用于关闭锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key) ;
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺ID不能为空！");
        }

        // 1.更新数据库
        updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        // 3.返回数据
        return Result.ok();
    }
}
