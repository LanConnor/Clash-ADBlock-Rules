package com.github.lanconnor.clashrules;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * YAML / JSON 读取工具（校验阶段使用）。
 *
 * @author Connor.Wang
 */
final class YamlSupport {

    private YamlSupport() {
    }

    /**
     * 安全解析 YAML 或 JSON 文件。
     *
     * @param path 文件路径
     * @return 解析结果（Map/List/String/Number/Boolean）
     * @throws IOException 读取失败
     */
    static Object load(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return yaml.load(reader);
        }
    }

    /**
     * 解析顶层为映射的文件。
     *
     * @param path 文件路径
     * @return 顶层映射，结构不符时返回空映射
     * @throws IOException 读取失败
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> loadMap(Path path) throws IOException {
        Object value = load(path);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
