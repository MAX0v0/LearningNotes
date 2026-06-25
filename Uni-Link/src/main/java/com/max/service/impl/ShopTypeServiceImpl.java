package com.max.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.max.dto.Result;
import com.max.entity.ShopType;
import com.max.mapper.ShopTypeMapper;
import com.max.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.max.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryAll() {
        //使用Redis缓存查询商户类型
        //先查redis
        String cacheShopType = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KET);

        //有,直接返回
        if (StrUtil.isNotBlank(cacheShopType)) {
            List<ShopType> shopTypes = JSONUtil.toList(cacheShopType, ShopType.class);
            return Result.ok(shopTypes);
        }

        //没有,查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
          //没有,返回错误信息
          if(shopTypes.isEmpty()){
              return Result.fail("服务器异常");
          }
          //有,存redis并返回
          stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KET, JSONUtil.toJsonStr(shopTypes));
          return Result.ok(shopTypes);
    }
}
