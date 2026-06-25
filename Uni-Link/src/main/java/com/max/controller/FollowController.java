package com.max.controller;


import com.max.dto.Result;
import com.max.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 *
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable Boolean isFollow ){
        return followService.follow(followUserId,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);

    }

    @GetMapping("/common/{id}")//id是被查询者的id,此函数作用为查询共同关注
    public Result followComments(@PathVariable Long id){
        return followService.followComments(id);
    }



}
