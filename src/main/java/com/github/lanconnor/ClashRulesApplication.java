package com.github.lanconnor;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Spring Boot 入口：以命令行方式运行规则转换流程，然后用退出码结束进程。
 *
 * @author Connor.Wang
 */
@SpringBootApplication
public class ClashRulesApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ClashRulesApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        ConfigurableApplicationContext context = application.run(args);
        System.exit(SpringApplication.exit(context));
    }
}
