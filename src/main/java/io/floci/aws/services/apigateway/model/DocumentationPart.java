package io.floci.aws.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentationPart {
    private String id;
    private DocumentationPartLocation location;
    private String properties;

    public DocumentationPart() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public DocumentationPartLocation getLocation() { return location; }
    public void setLocation(DocumentationPartLocation location) { this.location = location; }

    public String getProperties() { return properties; }
    public void setProperties(String properties) { this.properties = properties; }
}
