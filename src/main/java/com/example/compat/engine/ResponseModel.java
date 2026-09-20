package com.example.compat.engine;

import java.util.TreeMap;

public final class ResponseModel {
    private final String status;
    private final String description;
    private final TreeMap<String, ESchema> mediaTypes;
    private final TreeMap<String, ParamModel> headers;

    public ResponseModel(String status, String description,
                         TreeMap<String, ESchema> mediaTypes,
                         TreeMap<String, ParamModel> headers) {
        this.status = status;
        this.description = description;
        this.mediaTypes = mediaTypes;
        this.headers = headers;
    }

    public String getStatus() { return status; }
    public String getDescription() { return description; }
    public TreeMap<String, ESchema> getMediaTypes() { return mediaTypes; }
    public TreeMap<String, ParamModel> getHeaders() { return headers; }
}
