package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JSON 1.1 handler path for API Gateway v2 integrations: Description sent with
 * CreateIntegration or UpdateIntegration must come back in the response and from
 * GetIntegration, on both the PascalCase wire shape and the stored model.
 */
class ApiGatewayV2IntegrationDescriptionJson11Test {

    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApiGatewayV2Service service;
    private ApiGatewayV2JsonHandler handler;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        service = new ApiGatewayV2Service(storageFactory, config, regionResolver);
        handler = new ApiGatewayV2JsonHandler(service, null, objectMapper);
    }

    private String apiId() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "description-api");
        request.put("protocolType", "HTTP");
        return service.createApi(REGION, request).getApiId();
    }

    private JsonNode body(String apiId, String integrationId, String description) throws Exception {
        StringBuilder json = new StringBuilder("{\"ApiId\":\"").append(apiId).append("\"");
        if (integrationId != null) {
            json.append(",\"IntegrationId\":\"").append(integrationId).append("\"");
        }
        json.append(",\"IntegrationType\":\"HTTP_PROXY\"");
        json.append(",\"IntegrationMethod\":\"GET\"");
        json.append(",\"IntegrationUri\":\"https://example.com\"");
        json.append(",\"PayloadFormatVersion\":\"1.0\"");
        if (description != null) {
            json.append(",\"Description\":\"").append(description).append("\"");
        }
        json.append("}");
        return objectMapper.readTree(json.toString());
    }

    private JsonNode entity(Response response) throws Exception {
        return objectMapper.readTree((String) response.getEntity());
    }

    @Test
    void json11CreateIntegrationReturnsDescription() throws Exception {
        String apiId = apiId();
        Response response = handler.handle("CreateIntegration", body(apiId, null, "probe"), REGION);

        assertEquals(201, response.getStatus());
        assertEquals("probe", entity(response).get("Description").asText());
    }

    @Test
    void json11UpdateIntegrationChangesDescription() throws Exception {
        String apiId = apiId();
        JsonNode created = entity(handler.handle("CreateIntegration", body(apiId, null, "probe"), REGION));
        String integrationId = created.get("IntegrationId").asText();

        Response updated = handler.handle("UpdateIntegration", body(apiId, integrationId, "probe2"), REGION);

        assertEquals(200, updated.getStatus());
        assertEquals("probe2", entity(updated).get("Description").asText());

        JsonNode fetched = entity(handler.handle("GetIntegration",
                objectMapper.readTree("{\"ApiId\":\"" + apiId + "\",\"IntegrationId\":\"" + integrationId + "\"}"),
                REGION));
        assertEquals("probe2", fetched.get("Description").asText());
    }
}
