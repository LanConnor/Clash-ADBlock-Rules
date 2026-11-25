package com.github.lanconnor.clashrules;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 生成 meta.json：构建时间、文件清单、内容指纹。
 *
 * <p>content_hash 对全部产物内容做合并指纹，工作流用它判断
 * 「这次构建和线上那份有没有区别」，没变化就不推送。</p>
 *
 * @author Connor.Wang
 */
@Component
public class MetaService {

    /**
     * 执行 meta 子命令。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws Exception 构建失败
     */
    public int run(String[] args) throws Exception {
        Path build = Path.of(args.length > 0 ? args[0] : "build");
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(build)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !"meta.json".equals(path.getFileName().toString()))
                    .forEach(paths::add);
        }
        paths.sort((a, b) -> Utils.compareCodePoints(relative(build, a), relative(build, b)));

        MessageDigest combined = MessageDigest.getInstance("SHA-256");
        Map<String, Object> files = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Path path : paths) {
            byte[] data = Files.readAllBytes(path);
            String relative = relative(build, path);
            String hash = Utils.sha256Hex(data);
            files.put(relative, hash);
            combined.update(relative.getBytes(StandardCharsets.UTF_8));
            combined.update(hash.getBytes(StandardCharsets.UTF_8));
            totalBytes += data.length;
        }
        String contentHash = HexFormat.of().formatHex(combined.digest());

        String upstreamTime = "";
        int adblockEntries = 0;
        Path indexPath = build.resolve("index.json");
        if (Files.exists(indexPath)) {
            Map<String, Object> index = YamlSupport.loadMap(indexPath);
            upstreamTime = String.valueOf(index.getOrDefault("upstream_build_time", ""));
            if (index.get("common") instanceof Map<?, ?> common
                    && common.get("adblock") instanceof Map<?, ?> adblock
                    && adblock.get("entries") instanceof Number entries) {
                adblockEntries = entries.intValue();
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        meta.put("upstream_build_time", upstreamTime);
        meta.put("content_hash", contentHash);
        meta.put("file_count", files.size());
        meta.put("total_bytes", totalBytes);
        meta.put("adblock_entries", adblockEntries);
        meta.put("files", files);
        Utils.writeUtf8(build.resolve("meta.json"), JsonWriter.write(meta));

        System.out.println("content_hash = " + contentHash);
        System.out.printf("files = %d, bytes = %.1f MB%n", files.size(), totalBytes / 1048576.0);
        return 0;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
