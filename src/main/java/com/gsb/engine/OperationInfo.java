package com.gsb.engine;

import java.util.List;
import java.util.Map;

public record OperationInfo(
        String method,
        String path,
        String operationId,
        List<ParamInfo> params,
        MediaBody requestBody,
        Map<String, MediaBody> responses
) {
    public String key() {
        return method.toUpperCase() + " " + path;
    }
}
