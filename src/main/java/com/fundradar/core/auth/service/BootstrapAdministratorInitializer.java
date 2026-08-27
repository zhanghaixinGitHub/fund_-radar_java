package com.fundradar.core.auth.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用在 Flyway 完成后检查是否需要由环境变量创建首位管理员。 */
@Component
public class BootstrapAdministratorInitializer implements ApplicationRunner {

    private final AccountService accountService;

    public BootstrapAdministratorInitializer(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 在无管理员时执行一次安全自举；密码缺失不会阻塞服务启动。 */
    @Override
    public void run(ApplicationArguments args) {
        accountService.bootstrapAdministratorIfRequired();
    }
}
