package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigatewayv2.model.Api;
import io.floci.aws.services.apigatewayv2.model.Integration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Regression tests for floci issue #7998: CreateIntegration and UpdateIntegration
 * drop the Description, so GetIntegration always returns null.
 */
class ApiGatewayV2IntegrationDescriptionTest {

    private static final String REGION = "us-east-1";

    private ApiGatewayV2Service service;
    private ApiGatewayV2JsonHandler handler;
    private ObjectMapper mapper;
    private String apiId;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new ApiGatewayV2Service(storageFactory, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, "000000000000"));
        mapper = new ObjectMapper();
        handler = new ApiGatewayV2JsonHandler(service,
                Mockito.mock(ApiGatewayV2OpenApiImporter.class), mapper);
        Api api = service.createApi(REGION, Map.of("name", "a1", "protocolType", "HTTP"));
        apiId = api.getApiId();
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("test fixture is not valid JSON: " + body, e);
        }
    }

    private JsonNode entityJson(Response response) {
        assertEquals(200, response.getStatus());
        try {
            return mapper.readTree(String.valueOf(response.getEntity()));
        } catch (Exception e) {
            throw new IllegalArgumentException("response entity is not valid JSON: " + response.getEntity(), e);
        }
    }

    private Map<String, Object> integrationRequest(String description) {
        Map<String, Object> request = new HashMap<>();
        request.put("integrationType", "HTTP_PROXY");
        request.put("integrationMethod", "GET");
        request.put("integrationUri", "https://example.com");
        request.put("payloadFormatVersion", "1.0");
        if (description != null) {
            request.put("description", description);
        }
        return request;
    }

    @Test
    void createIntegrationStoresDescription() {
        Integration created = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        assertEquals("probe", created.getDescription());
        assertEquals("probe", service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void createIntegrationWithoutDescriptionIsNull() {
        Integration created = service.createIntegration(REGION, apiId, integrationRequest(null));

        assertNull(created.getDescription());
    }

    @Test
    void updateIntegrationChangesDescription() {
        Integration created = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        Integration updated = service.updateIntegration(REGION, apiId, created.getIntegrationId(),
                integrationRequest("probe2"));

        assertEquals("probe2", updated.getDescription());
        assertEquals("probe2", service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void updateIntegrationWithoutDescriptionKeepsExisting() {
        Integration created = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        Map<String, Object> update = integrationRequest(null);
        update.put("integrationMethod", "POST");
        Integration updated = service.updateIntegration(REGION, apiId, created.getIntegrationId(), update);

        assertEquals("probe", updated.getDescription());
    }

    @Test
    void copyConstructorPreservesDescription() {
        Integration created = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        service.restoreIntegration(REGION, apiId, new Integration(created));

        assertEquals("probe", service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void jsonHandlerCreateAndGetIntegrationReturnDescription() {
        Response created = handler.handle("CreateIntegration", json("""
                {"ApiId": "%s", "IntegrationType": "HTTP_PROXY", "IntegrationMethod": "GET",
                 "IntegrationUri": "https://example.com", "PayloadFormatVersion": "1.0",
                 "Description": "probe"}
                """.formatted(apiId)), REGION);

        assertEquals(201, created.getStatus());
        JsonNode createdNode;
        try {
            createdNode = mapper.readTree(String.valueOf(created.getEntity()));
        } catch (Exception e) {
            throw new IllegalArgumentException("response entity is not valid JSON: " + created.getEntity(), e);
        }
        assertEquals("probe", createdNode.path("Description").asText());
        String integrationId = createdNode.path("IntegrationId").asText();

        JsonNode fetched = entityJson(handler.handle("GetIntegration", json("""
                {"ApiId": "%s", "IntegrationId": "%s"}
                """.formatted(apiId, integrationId)), REGION));

        assertEquals("probe", fetched.path("Description").asText());
    }

    @Test
    void jsonHandlerUpdateIntegrationChangesDescription() {
        Response created = handler.handle("CreateIntegration", json("""
                {"ApiId": "%s", "IntegrationType": "HTTP_PROXY", "IntegrationMethod": "GET",
                 "IntegrationUri": "https://example.com", "PayloadFormatVersion": "1.0",
                 "Description": "probe"}
                """.formatted(apiId)), REGION);
        String integrationId;
        try {
            integrationId = mapper.readTree(String.valueOf(created.getEntity())).path("IntegrationId").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("response entity is not valid JSON: " + created.getEntity(), e);
        }

        JsonNode updated = entityJson(handler.handle("UpdateIntegration", json("""
                {"ApiId": "%s", "IntegrationId": "%s", "Description": "probe2"}
                """.formatted(apiId, integrationId)), REGION));

        assertEquals("probe2", updated.path("Description").asText());

        JsonNode fetched = entityJson(handler.handle("GetIntegration", json("""
                {"ApiId": "%s", "IntegrationId": "%s"}
                """.formatted(apiId, integrationId)), REGION));

        assertEquals("probe2", fetched.path("Description").asText());
    }
}
