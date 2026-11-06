package io.floci.aws.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentationPartLocation {
    private String type;
    private String path;
    private String method;
    private String statusCode;
    private String name;

    public DocumentationPartLocation() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
