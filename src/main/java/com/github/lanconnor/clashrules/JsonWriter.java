package com.github.lanconnor.clashrules;

import java.util.List;
import java.util.Map;

/**
 * 按 Python json.dump(ensure_ascii=False, indent=2) 的格式序列化 JSON，
 * 保证 index.json / meta.json 的字节稳定，切换语言后产物不产生无关 diff。
 *
 * @author Connor.Wang
 */
final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * 序列化 JSON。
     *
     * @param value 由 Map/List/String/Number/Boolean 组成的结构
     * @return JSON 文本（无结尾换行）
     */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, 0, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, int indent, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, indent, sb);
        } else if (value instanceof List) {
            writeList((List<?>) value, indent, sb);
        } else {
            throw new IllegalArgumentException("不支持的 JSON 类型: " + value.getClass());
        }
    }

    private static void writeMap(Map<?, ?> map, int indent, StringBuilder sb) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sb.append(spaces(indent + 2));
            writeString(String.valueOf(entry.getKey()), sb);
            sb.append(": ");
            writeValue(entry.getValue(), indent + 2, sb);
            if (++index < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(spaces(indent)).append('}');
    }

    private static void writeList(List<?> list, int indent, StringBuilder sb) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(spaces(indent + 2));
            writeValue(list.get(i), indent + 2, sb);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(spaces(indent)).append(']');
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static String spaces(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
