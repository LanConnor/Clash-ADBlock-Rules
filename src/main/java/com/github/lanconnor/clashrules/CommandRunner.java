package com.github.lanconnor.clashrules;

import jakarta.annotation.Resource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * 把命令行参数交给分发器，并把退出码带回 Spring 容器。
 *
 * @author Connor.Wang
 */
@Component
public class CommandRunner implements CommandLineRunner, ExitCodeGenerator {

    @Resource
    private CommandDispatcher commandDispatcher;

    private int exitCode;

    @Override
    public void run(String... args) throws Exception {
        exitCode = commandDispatcher.dispatch(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
