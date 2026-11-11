package io.floci.aws.services.apigatewayv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigatewayv2.model.Integration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API Gateway v2 integrations keep their Description across create, update and get: a
 * description set on CreateIntegration or UpdateIntegration must come back from
 * GetIntegration instead of null.
 */
class ApiGatewayV2IntegrationDescriptionServiceTest {

    private static final String REGION = "us-east-1";

    private ApiGatewayV2Service service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        service = new ApiGatewayV2Service(storageFactory, config, regionResolver);
    }

    private String apiId() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "description-api");
        request.put("protocolType", "HTTP");
        return service.createApi(REGION, request).getApiId();
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
    void createIntegrationKeepsDescription() {
        String apiId = apiId();
        Integration integration = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        assertEquals("probe", integration.getDescription());
        assertEquals("probe",
                service.getIntegration(REGION, apiId, integration.getIntegrationId()).getDescription());
    }

    @Test
    void createIntegrationWithoutDescriptionStaysNull() {
        String apiId = apiId();
        Integration integration = service.createIntegration(REGION, apiId, integrationRequest(null));

        assertNull(integration.getDescription());
    }

    @Test
    void updateIntegrationChangesDescription() {
        String apiId = apiId();
        Integration integration = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        Integration updated = service.updateIntegration(REGION, apiId, integration.getIntegrationId(),
                Map.of("description", "probe2"));

        assertEquals("probe2", updated.getDescription());
        assertEquals("probe2",
                service.getIntegration(REGION, apiId, integration.getIntegrationId()).getDescription());
    }

    @Test
    void updateIntegrationWithoutDescriptionKeepsExisting() {
        String apiId = apiId();
        Integration integration = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        Integration updated = service.updateIntegration(REGION, apiId, integration.getIntegrationId(),
                Map.of("integrationMethod", "POST"));

        assertEquals("probe", updated.getDescription());
    }

    @Test
    void integrationCopyKeepsDescription() {
        String apiId = apiId();
        Integration integration = service.createIntegration(REGION, apiId, integrationRequest("probe"));

        Integration copy = new Integration(integration);

        assertEquals("probe", copy.getDescription());
    }
}
