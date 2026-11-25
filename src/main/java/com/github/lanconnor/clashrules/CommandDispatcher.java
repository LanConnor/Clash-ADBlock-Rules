package com.github.lanconnor.clashrules;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 命令行子命令分发。
 *
 * @author Connor.Wang
 */
@Component
public class CommandDispatcher {

    @Resource
    private BuildService buildService;

    @Resource
    private MetaService metaService;

    @Resource
    private ValidateService validateService;

    @Resource
    private SummaryService summaryService;

    @Resource
    private Sr2ClashService sr2ClashService;

    /**
     * 分发给对应服务并返回退出码。
     *
     * @param args 完整命令行参数
     * @return 退出码
     * @throws Exception 子命令执行失败
     */
    public int dispatch(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: java -jar clash-rules.jar <build|meta|validate|summary|sr2clash> [参数...]");
            return 2;
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (command) {
                case "build" -> buildService.run(rest);
                case "meta" -> metaService.run(rest);
                case "validate" -> validateService.run(rest);
                case "summary" -> summaryService.run(rest);
                case "sr2clash" -> sr2ClashService.run(rest);
                default -> {
                    System.err.println("未知命令: " + command);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println("[!] " + e.getMessage());
            return 2;
        }
    }
}
