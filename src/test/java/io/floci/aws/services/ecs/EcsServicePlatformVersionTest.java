package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.HostVolumePolicy;
import io.floci.aws.services.ecs.model.LaunchType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A service keeps the platform version it was asked for (floci-io/floci#7997): AWS
 * keeps {@code LATEST} as {@code LATEST} on the service and only its tasks report
 * the resolved version ({@code 1.4.0}), so a Terraform {@code aws_ecs_service} with
 * {@code platform_version = "LATEST"} must not show a diff on every plan.
 *
 * <p>Plain unit test: the service is assembled directly (mock mode, file-backed
 * storage under a temp dir) and exercised through {@link EcsJsonHandler}, the
 * same JSON 1.1 path the CLI hits.
 */
class EcsServicePlatformVersionTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "platform-version-cluster";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createServiceWithLatestReportsLatest(@TempDir Path dataDir) throws Exception {
        EcsJsonHandler handler = handler(dataDir);
        seed(handler, "pv-latest-fam");

        JsonNode created = call(handler, "CreateService", """
                {"cluster":"%s","serviceName":"pv-latest-svc","taskDefinition":"pv-latest-fam",
                 "desiredCount":0,"launchType":"FARGATE",
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-1"]}},
                 "platformVersion":"LATEST"}""".formatted(CLUSTER));
        assertEquals("LATEST", created.path("service").path("platformVersion").asText(),
                "CreateService must echo the requested LATEST, not the resolved 1.4.0");

        JsonNode described = call(handler, "DescribeServices",
                "{\"cluster\":\"%s\",\"services\":[\"pv-latest-svc\"]}".formatted(CLUSTER));
        assertEquals("LATEST", described.path("services").path(0).path("platformVersion").asText(),
                "DescribeServices must keep LATEST on the service");
    }

    @Test
    void createServiceWithoutPlatformVersionDefaultsToLatest(@TempDir Path dataDir) throws Exception {
        EcsJsonHandler handler = handler(dataDir);
        seed(handler, "pv-default-fam");

        JsonNode created = call(handler, "CreateService", """
                {"cluster":"%s","serviceName":"pv-default-svc","taskDefinition":"pv-default-fam",
                 "desiredCount":0,"launchType":"FARGATE",
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-1"]}}}"""
                .formatted(CLUSTER));
        assertEquals("LATEST", created.path("service").path("platformVersion").asText(),
                "an omitted platformVersion defaults to LATEST, the version AWS reports");
    }

    @Test
    void updateServiceToLatestKeepsLatestWithoutRollingDeployment(@TempDir Path dataDir) throws Exception {
        EcsJsonHandler handler = handler(dataDir);
        seed(handler, "pv-update-fam");

        JsonNode created = call(handler, "CreateService", """
                {"cluster":"%s","serviceName":"pv-update-svc","taskDefinition":"pv-update-fam",
                 "desiredCount":0,"launchType":"FARGATE",
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-1"]}},
                 "platformVersion":"LATEST"}""".formatted(CLUSTER));
        String deploymentId = created.path("service").path("deployments").path(0).path("id").asText();

        JsonNode updated = call(handler, "UpdateService",
                "{\"cluster\":\"%s\",\"service\":\"pv-update-svc\",\"platformVersion\":\"LATEST\"}"
                        .formatted(CLUSTER));
        assertEquals("LATEST", updated.path("service").path("platformVersion").asText());
        assertEquals(deploymentId,
                updated.path("service").path("deployments").path(0).path("id").asText(),
                "asking for LATEST on a service already at LATEST is not a change");

        JsonNode rolled = call(handler, "UpdateService",
                "{\"cluster\":\"%s\",\"service\":\"pv-update-svc\",\"platformVersion\":\"1.3.0\"}"
                        .formatted(CLUSTER));
        assertEquals("1.3.0", rolled.path("service").path("platformVersion").asText(),
                "a concrete version is kept verbatim");
        assertNotEquals(deploymentId,
                rolled.path("service").path("deployments").path(0).path("id").asText(),
                "a changed platformVersion must roll the deployment");
    }

    @Test
    void createServiceWithConcreteVersionKeepsItVerbatim(@TempDir Path dataDir) throws Exception {
        EcsJsonHandler handler = handler(dataDir);
        seed(handler, "pv-concrete-fam");

        JsonNode created = call(handler, "CreateService", """
                {"cluster":"%s","serviceName":"pv-concrete-svc","taskDefinition":"pv-concrete-fam",
                 "desiredCount":0,"launchType":"FARGATE",
                 "networkConfiguration":{"awsvpcConfiguration":{"subnets":["subnet-1"]}},
                 "platformVersion":"1.3.0"}""".formatted(CLUSTER));
        assertEquals("1.3.0", created.path("service").path("platformVersion").asText());
    }

    private static void seed(EcsJsonHandler handler, String family) throws Exception {
        call(handler, "CreateCluster", "{\"clusterName\":\"%s\"}".formatted(CLUSTER));
        call(handler, "RegisterTaskDefinition", """
                {"family":"%s","requiresCompatibilities":["FARGATE"],"networkMode":"awsvpc",
                 "cpu":"256","memory":"512",
                 "containerDefinitions":[{"name":"app","image":"nginx:latest"}]}""".formatted(family));
    }

    private static JsonNode call(EcsJsonHandler handler, String action, String body) throws Exception {
        Response response = handler.handle(action, MAPPER.readTree(body), REGION);
        return MAPPER.readTree(response.getEntity().toString());
    }

    private static EcsJsonHandler handler(Path dataDir) {
        EcsService service = serviceWithStorage(new FileStorageFactory(dataDir));
        return new EcsJsonHandler(service, MAPPER,
                new HostVolumePolicy(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS)));
    }

    private static EcsService serviceWithStorage(StorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storage,
                null);
        service.initializeStorage();
        return service;
    }

    /** Backs every store with a real {@link PersistentStorage} file, so values are serialized on put. */
    private static final class FileStorageFactory extends StorageFactory {

        private final Path dataDir;
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private FileStorageFactory(Path dataDir) {
            super(null, null);
            this.dataDir = dataDir;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> persistent =
                        new PersistentStorage<>(dataDir.resolve(fileName), typeReference);
                persistent.load();
                return new AccountAwareStorageBackend<>(persistent, null, "000000000000");
            });
        }
    }
}
