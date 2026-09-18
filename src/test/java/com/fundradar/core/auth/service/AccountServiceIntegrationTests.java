package com.fundradar.core.auth.service;

import com.fundradar.core.auth.api.AdminUserPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 PostgreSQL 事务内验证后台账户清单的关键字过滤，数据在用例结束后回滚。 */
@SpringBootTest(properties = "simulation.enabled=false")
@Transactional
class AccountServiceIntegrationTests {

    @Autowired AccountService accountService;
    @Autowired JdbcClient db;

    private String createUser(String displayName) {
        String mobile = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) "
                        + "VALUES (:id,:mobile,:displayName,'test-only-hash','FUND_USER','ACTIVE')")
                .param("id", UUID.randomUUID())
                .param("mobile", mobile)
                .param("displayName", displayName)
                .update();
        return mobile;
    }

    @Test
    void listUsersFiltersByNameSubstringOrFullMobile() {
        String unique = "关键字" + UUID.randomUUID().toString().substring(0, 8);
        String mobile = createUser(unique + "甲");
        createUser(unique + "乙");
        createUser("不相关账户" + UUID.randomUUID().toString().substring(0, 8));

        AdminUserPageResponse byName = accountService.listUsers(0, 20, unique);
        assertEquals(2, byName.total());
        assertEquals(2, byName.items().size());

        AdminUserPageResponse byMobile = accountService.listUsers(0, 20, mobile);
        assertEquals(1, byMobile.total());
        assertEquals(unique + "甲", byMobile.items().get(0).displayName());

        AdminUserPageResponse none = accountService.listUsers(0, 20, "一定不存在的名字xyz");
        assertEquals(0, none.total());
        assertTrue(none.items().isEmpty());

        AdminUserPageResponse blank = accountService.listUsers(0, 100, "  ");
        assertTrue(blank.total() >= 3);
    }
}
