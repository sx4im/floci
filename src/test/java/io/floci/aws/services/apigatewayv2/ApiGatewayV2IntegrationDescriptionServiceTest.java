package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.floci.aws.services.apigatewayv2.model.Integration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class ApiGatewayV2IntegrationDescriptionServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ApiGatewayV2Service service = new ApiGatewayV2Service(
            new InMemoryStorageFactory(), mock(EmulatorConfig.class),
            new RegionResolver(REGION, ACCOUNT));

    @Test
    void createIntegrationPersistsDescription() {
        String apiId = createApi();
        Map<String, Object> request = integrationRequest();
        request.put("description", "probe");

        Integration created = service.createIntegration(REGION, apiId, request);

        assertEquals("probe", created.getDescription());
        assertEquals("probe", service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void updateIntegrationChangesDescription() {
        String apiId = createApi();
        Map<String, Object> request = integrationRequest();
        request.put("description", "probe");
        Integration created = service.createIntegration(REGION, apiId, request);

        Map<String, Object> update = new HashMap<>();
        update.put("description", "probe2");
        Integration updated = service.updateIntegration(REGION, apiId, created.getIntegrationId(), update);

        assertEquals("probe2", updated.getDescription());
        assertEquals("probe2", service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void updateIntegrationKeepsDescriptionWhenOmitted() {
        String apiId = createApi();
        Map<String, Object> request = integrationRequest();
        request.put("description", "probe");
        Integration created = service.createIntegration(REGION, apiId, request);

        Map<String, Object> update = new HashMap<>();
        update.put("integrationMethod", "POST");
        Integration updated = service.updateIntegration(REGION, apiId, created.getIntegrationId(), update);

        assertEquals("probe", updated.getDescription());
        assertEquals("POST", updated.getIntegrationMethod());
    }

    @Test
    void createIntegrationWithoutDescriptionLeavesItNull() {
        String apiId = createApi();

        Integration created = service.createIntegration(REGION, apiId, integrationRequest());

        assertNull(created.getDescription());
        assertNull(service.getIntegration(REGION, apiId, created.getIntegrationId()).getDescription());
    }

    @Test
    void copyConstructorKeepsDescription() {
        Integration source = new Integration();
        source.setIntegrationId("abc12345");
        source.setDescription("probe");

        assertEquals("probe", new Integration(source).getDescription());
    }

    private String createApi() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "a1");
        request.put("protocolType", "HTTP");
        return service.createApi(REGION, request).getApiId();
    }

    private static Map<String, Object> integrationRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("integrationType", "HTTP_PROXY");
        request.put("integrationMethod", "GET");
        request.put("integrationUri", "https://example.com");
        request.put("payloadFormatVersion", "1.0");
        return request;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference,
                                                       WriteProfile profile) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
