package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    public ShopServiceImpl shopService;

    /**
     * 对Redis进行预热
     */
    @Test
    void testSaveShop() {
        shopService.saveshop2Redis(1L, 30L);
    }
}
