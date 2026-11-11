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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JSON 1.1 handler path for API Gateway v2 integrations: a Description sent
 * with CreateIntegration or UpdateIntegration must come back in the response
 * body and from GetIntegration. Before the fix the value was accepted and
 * stored nowhere visible, so every read returned null.
 */
class ApiGatewayV2IntegrationDescriptionJson11Test {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApiGatewayV2Service service;
    private ApiGatewayV2JsonHandler handler;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        service = new ApiGatewayV2Service(storageFactory, config, regionResolver);
        handler = new ApiGatewayV2JsonHandler(service, null, objectMapper);
    }

    @Test
    void createIntegrationResponseIncludesDescription() throws Exception {
        String apiId = createApiId();
        Response response = handler.handle("CreateIntegration", integrationBody(apiId, null, "probe"), REGION);

        assertEquals(201, response.getStatus());
        assertEquals("probe", entity(response).get("Description").asText());
    }

    @Test
    void updateIntegrationResponseIncludesNewDescription() throws Exception {
        String apiId = createApiId();
        JsonNode created = entity(handler.handle("CreateIntegration", integrationBody(apiId, null, "probe"), REGION));
        String integrationId = created.get("IntegrationId").asText();

        Response updated = handler.handle("UpdateIntegration",
                integrationBody(apiId, integrationId, "probe2"), REGION);

        assertEquals(200, updated.getStatus());
        assertEquals("probe2", entity(updated).get("Description").asText());
    }

    @Test
    void getIntegrationReturnsUpdatedDescription() throws Exception {
        String apiId = createApiId();
        JsonNode created = entity(handler.handle("CreateIntegration", integrationBody(apiId, null, "probe"), REGION));
        String integrationId = created.get("IntegrationId").asText();
        handler.handle("UpdateIntegration", integrationBody(apiId, integrationId, "probe2"), REGION);

        Response fetched = handler.handle("GetIntegration", lookupBody(apiId, integrationId), REGION);

        assertEquals(200, fetched.getStatus());
        assertEquals("probe2", entity(fetched).get("Description").asText());
    }

    @Test
    void updateIntegrationWithoutDescriptionKeepsExisting() throws Exception {
        String apiId = createApiId();
        JsonNode created = entity(handler.handle("CreateIntegration", integrationBody(apiId, null, "probe"), REGION));
        String integrationId = created.get("IntegrationId").asText();

        Map<String, Object> update = new HashMap<>();
        update.put("ApiId", apiId);
        update.put("IntegrationId", integrationId);
        update.put("IntegrationMethod", "POST");
        Response updated = handler.handle("UpdateIntegration", objectMapper.valueToTree(update), REGION);

        assertEquals(200, updated.getStatus());
        assertEquals("probe", entity(updated).get("Description").asText());
    }

    private String createApiId() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "description-api");
        request.put("protocolType", "HTTP");
        return service.createApi(REGION, request).getApiId();
    }

    private JsonNode integrationBody(String apiId, String integrationId, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("ApiId", apiId);
        if (integrationId != null) {
            body.put("IntegrationId", integrationId);
        }
        body.put("IntegrationType", "HTTP_PROXY");
        body.put("IntegrationMethod", "GET");
        body.put("IntegrationUri", "https://example.com");
        body.put("PayloadFormatVersion", "1.0");
        if (description != null) {
            body.put("Description", description);
        }
        return objectMapper.valueToTree(body);
    }

    private JsonNode lookupBody(String apiId, String integrationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ApiId", apiId);
        body.put("IntegrationId", integrationId);
        return objectMapper.valueToTree(body);
    }

    private JsonNode entity(Response response) throws Exception {
        return objectMapper.readTree((String) response.getEntity());
    }
}
