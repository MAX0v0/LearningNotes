package com.max.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.max.dto.Result;
import com.max.dto.ScrollResult;
import com.max.dto.UserDTO;
import com.max.entity.Blog;
import com.max.entity.Follow;
import com.max.entity.User;
import com.max.mapper.BlogMapper;
import com.max.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.max.service.IFollowService;
import com.max.service.IUserService;
import com.max.utils.RedisConstants;
import com.max.utils.SystemConstants;
import com.max.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 *
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //查询Blog
        Blog blog = getById(id);
        if(blog==null){return Result.fail("博客不存在");}
        //查询Blog有关用户
        queryBlogUser(blog);
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user =UserHolder.getUser();
        if(user==null){
            //用户未登录
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //判断是否点赞过
        String key= RedisConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是否点赞过
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //未点赞
        if(score==null){
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if(isSuccess){//key,value,score
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }

        }else{//已点赞
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
            }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        //查询top5查询范围
        Set<String> top5UserId = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5UserId==null || top5UserId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        Collection<Long> UserIds = top5UserId.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据用户id查用户
        String idStr = StrUtil.join(",", UserIds);
        List<UserDTO> userDTOS = userService.query().in("id",UserIds).last("ORDER BY FIELD(id,"+idStr+")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        log.debug("saveBlog user={}", user);
        blog.setUserId(user.getId());
        //保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){return Result.fail("新增笔记失败");}
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id=?(user.getId())
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有的粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key=RedisConstants.FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱ZREVRANGEBTSCORE key Max Min WITHSCORES LIMIT offset count
        String key=RedisConstants.FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);//参数依次为key,最小值,最大值,偏移量,每一页展示个数
        if(typedTuples==null || typedTuples.isEmpty()){return Result.ok();}
        //3.解析收件箱里面的数据(笔记id,时间戳(score),offset)
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else{
                minTime=time;
                os=1;
            }

        }
        //4.根据id找到blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询Blog有关用户
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
