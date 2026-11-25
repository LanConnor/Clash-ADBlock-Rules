package com.github.lanconnor.clashrules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简命令行参数解析：支持 `--key value`、`-x value` 和布尔开关。
 *
 * @author Connor.Wang
 */
final class Cli {

    private final Map<String, List<String>> options = new LinkedHashMap<>();
    private final List<String> positionals = new ArrayList<>();

    private Cli() {
    }

    /**
     * 解析参数。
     *
     * @param args    原始参数
     * @param valued  需要取值的选项
     * @param flags   布尔开关
     * @return 解析结果
     */
    static Cli parse(String[] args, Set<String> valued, Set<String> flags) {
        Cli cli = new Cli();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                cli.positionals.add(arg);
                continue;
            }
            if (valued.contains(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " 缺少参数值");
                }
                cli.options.computeIfAbsent(arg, key -> new ArrayList<>()).add(args[++i]);
            } else if (flags.contains(arg)) {
                cli.options.computeIfAbsent(arg, key -> new ArrayList<>()).add("");
            } else {
                throw new IllegalArgumentException("未知参数: " + arg);
            }
        }
        return cli;
    }

    /**
     * 判断开关是否存在。
     *
     * @param name 选项名
     * @return 是否存在
     */
    boolean has(String name) {
        return options.containsKey(name);
    }

    /**
     * 取选项最后一个值。
     *
     * @param name 选项名
     * @return 选项值，未提供时返回 null
     */
    String value(String name) {
        List<String> values = options.get(name);
        return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /**
     * 取选项值，未提供时返回默认值。
     *
     * @param name         选项名
     * @param defaultValue 默认值
     * @return 选项值
     */
    String value(String name, String defaultValue) {
        String value = value(name);
        return value == null ? defaultValue : value;
    }

    /**
     * 取选项的全部值（可重复参数）。
     *
     * @param name 选项名
     * @return 值的列表
     */
    List<String> values(String name) {
        List<String> values = options.get(name);
        return values == null ? List.of() : values;
    }

    /**
     * 取第 N 个位置参数。
     *
     * @param index 下标
     * @return 参数值，不存在时返回 null
     */
    String positional(int index) {
        return index < positionals.size() ? positionals.get(index) : null;
    }

    /**
     * 取第 N 个位置参数，不存在时返回默认值。
     *
     * @param index        下标
     * @param defaultValue 默认值
     * @return 参数值
     */
    String positional(int index, String defaultValue) {
        String value = positional(index);
        return value == null ? defaultValue : value;
    }
}
