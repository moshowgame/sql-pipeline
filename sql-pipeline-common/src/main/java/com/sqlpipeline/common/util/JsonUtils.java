package com.sqlpipeline.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;

import java.util.List;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw BizException.i18n(ErrorCode.SYS_INTERNAL, "error.json.serializeFailed", e);
        }
    }

    /** 反序列化失败时抛出带指定错误码的 BizException（用于入参校验场景）。 */
    public static <T> T fromJson(String json, Class<T> type, ErrorCode errorOnFail) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw BizException.i18n(errorOnFail, "error.json.parseFailed", e.getOriginalMessage());
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type, ErrorCode errorOnFail) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw BizException.i18n(errorOnFail, "error.json.parseFailed", e.getOriginalMessage());
        }
    }

    /** 解析 JSON 数组为 List（健康检查默认参数等场景）。 */
    public static List<Object> parseList(String json, ErrorCode errorOnFail) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw BizException.i18n(errorOnFail, "error.json.arrayParseFailed", e.getOriginalMessage());
        }
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
