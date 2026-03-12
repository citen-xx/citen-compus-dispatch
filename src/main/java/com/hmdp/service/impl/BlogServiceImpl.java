package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
        //查询blog
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);

        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user=UserHolder.getUser();
        if(user==null){
            return;
        }

        Long userId= user.getId();

        String key="blog:liked"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        getBlog(blog, user);
    }

    private static Blog getBlog(Blog blog, User user) {
        return blog.setIcon(user.getIcon());
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户、
        Long userId= UserHolder.getUser().getId();
        //判断当前登录用户是否可以点赞
        String key="blog:liked"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //如果没有点赞可以点赞
            //数据库点赞数加一
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //保存用户到redis的set集合
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //如果已经点赞，取消点赞
            //数据库点赞数-1
            //把用户从REdis的set集合移除
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=BLOG_LIKED_KEY+id;
        //查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //解析出来其中的用户id
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据用户id查询用户
        List<UserDTO> userDTOS = userService.query().in("id",ids).last("order by field(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user=UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for(Follow follow:follows){
            Long userId = follow.getUserId();
            //推送
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogofFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, SystemConstants.MAX_PAGE_SIZE);

        //非空判断
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }

        //解析数据 时间戳，offset
        List<Long>ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for(  ZSetOperations.TypedTuple<String> typed:typedTuples){
            //获取id
            String idStr = typed.getValue();
            ids.add(Long.valueOf(idStr));
            //获取时间戳
            long time = typed.getScore().longValue();
            if(time==minTime){
                os++;
            }else{
                minTime=time;
                os=1;
            }
        }

        //根据id查询blog
        String idStr=StrUtil.join(",",ids);
        List<Blog>blogs=query().in("id",ids).last("order by field(id,"+idStr+")").list();
        //封装并且返回
        for(Blog blog:blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}