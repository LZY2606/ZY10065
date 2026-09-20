package com.example.compat.engine;

import java.util.List;
import java.util.TreeMap;

/** One (method, path) operation after parameter inheritance has been applied. */
public final class OperationModel {
    private final String method;
    private final String path;
    private final String operationId;
    private final List<ParamModel> parameters;
    private final BodyModel requestBody;
    /** key: status code or "default"; value: response model. */
    private final TreeMap<String, ResponseModel> responses;

    public OperationModel(String method, String path, String operationId,
                          List<ParamModel> parameters, BodyModel requestBody,
                          TreeMap<String, ResponseModel> responses) {
        this.method = method;
        this.path = path;
        this.operationId = operationId;
        this.parameters = parameters;
        this.requestBody = requestBody;
        this.responses = responses;
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getOperationId() { return operationId; }
    public List<ParamModel> getParameters() { return parameters; }
    public BodyModel getRequestBody() { return requestBody; }
    public TreeMap<String, ResponseModel> getResponses() { return responses; }
}
