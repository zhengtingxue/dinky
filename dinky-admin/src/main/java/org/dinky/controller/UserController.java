/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.controller;

import org.dinky.assertion.Asserts;
import org.dinky.common.result.ProTableResult;
import org.dinky.common.result.Result;
import org.dinky.dto.ModifyPasswordDTO;
import org.dinky.model.User;
import org.dinky.model.UserTenant;
import org.dinky.service.UserService;
import org.dinky.service.UserTenantService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.lang.Dict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * UserController
 *
 * @author wenmo
 * @since 2021/11/28 13:43
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final UserTenantService userTenantService;

    /** 新增或者更新 */
    @PutMapping
    public Result<Void> saveOrUpdate(@RequestBody User user) {
        if (Asserts.isNull(user.getId())) {
            return userService.registerUser(user);
        } else {
            userService.modifyUser(user);
            return Result.succeed("修改成功");
        }
    }

    /** 动态查询列表 */
    @PostMapping
    public ProTableResult<User> listClusterConfigs(@RequestBody JsonNode para) {
        return userService.selectForProTable(para, true);
    }

    /** 批量删除 */
    @DeleteMapping
    public Result<Void> deleteMul(@RequestBody JsonNode para) {
        if (para.size() > 0) {
            List<Integer> error = new ArrayList<>();
            for (final JsonNode item : para) {
                Integer id = item.asInt();
                if (checkAdmin(id)) {
                    error.add(id);
                    continue;
                }
                if (!userService.removeUser(id)) {
                    error.add(id);
                }
            }
            if (error.size() == 0) {
                return Result.succeed("删除成功");
            } else {
                return Result.succeed("删除部分成功，但" + error + "删除失败，共" + error.size() + "次失败。");
            }
        } else {
            return Result.failed("请选择要删除的记录");
        }
    }

    private static boolean checkAdmin(Integer id) {
        return id == 0;
    }

    /** 获取指定ID的信息 */
    @PostMapping("/getOneById")
    public Result<User> getOneById(@RequestBody User user) {
        user = userService.getById(user.getId());
        user.setPassword(null);
        return Result.succeed(user, "获取成功");
    }

    /** 修改密码 */
    @PostMapping("/modifyPassword")
    public Result<Void> modifyPassword(@RequestBody ModifyPasswordDTO modifyPasswordDTO) {
        return userService.modifyPassword(
                modifyPasswordDTO.getUsername(),
                modifyPasswordDTO.getPassword(),
                modifyPasswordDTO.getNewPassword());
    }

    /**
     * give user grant role
     *
     * @param para param
     * @return {@link Result}
     */
    @PutMapping(value = "/grantRole")
    public Result<Void> grantRole(@RequestBody JsonNode para) {
        return userService.grantRole(para);
    }

    @GetMapping("/getUserListByTenantId")
    public Result<Dict> getUserListByTenantId(@RequestParam("id") Integer id) {
        List<User> userList = userService.list();

        List<UserTenant> userTenants =
                userTenantService
                        .getBaseMapper()
                        .selectList(new QueryWrapper<UserTenant>().eq("tenant_id", id));
        List<Integer> userIds = new ArrayList<>();
        for (UserTenant userTenant : userTenants) {
            userIds.add(userTenant.getUserId());
        }
        Dict result = Dict.create().set("users", userList).set("userIds", userIds);
        return Result.succeed(result, "获取成功");
    }
}
