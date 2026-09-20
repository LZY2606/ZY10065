package com.example.compat.engine;

import java.util.TreeMap;

public final class BodyModel {
    private final boolean required;
    /** key: media type */
    private final TreeMap<String, ESchema> mediaTypes;

    public BodyModel(boolean required, TreeMap<String, ESchema> mediaTypes) {
        this.required = required;
        this.mediaTypes = mediaTypes;
    }

    public boolean isRequired() { return required; }
    public TreeMap<String, ESchema> getMediaTypes() { return mediaTypes; }
}
