package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.CreateNodeGroupRequest;
import io.floci.aws.services.eks.model.Nodegroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * EKS cluster names are unique per Region, not per account: the same name in two Regions
 * is two clusters. Two services sharing one store, one per Region, exercise that.
 */
class EksClusterRegionKeyingTest {

    private static final String ACCOUNT = "000000000000";
    private static final String EAST = "us-east-1";
    private static final String WEST = "eu-west-1";

    private Map<String, AccountAwareStorageBackend<?>> stores;
    private EksService eastService;
    private EksService westService;
    private EksOidcService eastOidc;
    private EksOidcService westOidc;

    @BeforeEach
    void setUp() {
        stores = new HashMap<>();
        StorageFactory sharedFactory = new StorageFactory(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                AccountAwareStorageBackend<?> store = stores.get(fileName);
                if (store == null) {
                    store = AccountAwareStorageBackend.inMemory(ACCOUNT);
                    stores.put(fileName, store);
                }
                return (AccountAwareStorageBackend<V>) store;
            }
        };
        eastOidc = new EksOidcService(sharedFactory, new ObjectMapper());
        westOidc = new EksOidcService(sharedFactory, new ObjectMapper());
        eastService = new EksService(sharedFactory, testConfig(), new RegionResolver(EAST, ACCOUNT),
                null, mock(Ec2Service.class), eastOidc, mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));
        westService = new EksService(sharedFactory, testConfig(), new RegionResolver(WEST, ACCOUNT),
                null, mock(Ec2Service.class), westOidc, mock(EksAccessEntryService.class),
                mock(EksPodIdentityAssociationService.class));
    }

    @Test
    void createClusterSameNameInDifferentRegionsSucceeds() {
        Cluster east = eastService.createCluster(clusterRequest("shared-name"));
        Cluster west = westService.createCluster(clusterRequest("shared-name"));

        assertEquals("shared-name", east.getName());
        assertEquals("shared-name", west.getName());
        assertNotEquals(east.getArn(), west.getArn());
        assertTrue(east.getArn().contains(EAST), east.getArn());
        assertTrue(west.getArn().contains(WEST), west.getArn());
        assertEquals(east.getArn(), eastService.describeCluster("shared-name").getArn());
        assertEquals(west.getArn(), westService.describeCluster("shared-name").getArn());
        assertTrue(east.getIdentity().getOidc().getIssuer().contains(EAST));
        assertTrue(west.getIdentity().getOidc().getIssuer().contains(WEST));
    }

    @Test
    void createClusterDuplicateInSameRegionStillFails() {
        eastService.createCluster(clusterRequest("dup-name"));

        assertThrows(AwsException.class, () -> eastService.createCluster(clusterRequest("dup-name")));
        assertEquals("dup-name", eastService.describeCluster("dup-name").getName());
    }

    @Test
    void describeListAndDeleteAreRegionScoped() {
        eastService.createCluster(clusterRequest("scoped-name"));
        westService.createCluster(clusterRequest("scoped-name"));

        assertEquals(List.of("scoped-name"), eastService.listClusters());
        assertEquals(List.of("scoped-name"), westService.listClusters());

        eastService.deleteCluster("scoped-name");

        assertThrows(AwsException.class, () -> eastService.describeCluster("scoped-name"));
        assertEquals(List.of(), eastService.listClusters());
        assertEquals("scoped-name", westService.describeCluster("scoped-name").getName());
        assertEquals(List.of("scoped-name"), westService.listClusters());
    }

    @Test
    void nodeGroupSameNameInDifferentRegionsAreIndependent() {
        eastService.createCluster(clusterRequest("ng-cluster"));
        westService.createCluster(clusterRequest("ng-cluster"));

        Nodegroup eastGroup = eastService.createNodeGroup("ng-cluster", nodeGroupRequest("ng-1"));
        Nodegroup westGroup = westService.createNodeGroup("ng-cluster", nodeGroupRequest("ng-1"));

        assertNotEquals(eastGroup.getNodegroupArn(), westGroup.getNodegroupArn());
        assertTrue(eastGroup.getNodegroupArn().contains(EAST), eastGroup.getNodegroupArn());
        assertTrue(westGroup.getNodegroupArn().contains(WEST), westGroup.getNodegroupArn());
        assertEquals(eastGroup.getNodegroupArn(),
                eastService.describeNodeGroup("ng-cluster", "ng-1").getNodegroupArn());
        assertEquals(westGroup.getNodegroupArn(),
                westService.describeNodeGroup("ng-cluster", "ng-1").getNodegroupArn());
        assertEquals(List.of("ng-1"), eastService.listNodeGroups("ng-cluster"));
        assertEquals(List.of("ng-1"), westService.listNodeGroups("ng-cluster"));

        eastService.deleteNodeGroup("ng-cluster", "ng-1");

        assertEquals(List.of(), eastService.listNodeGroups("ng-cluster"));
        assertEquals(List.of("ng-1"), westService.listNodeGroups("ng-cluster"));
        assertEquals(westGroup.getNodegroupArn(),
                westService.describeNodeGroup("ng-cluster", "ng-1").getNodegroupArn());
    }

    @Test
    void legacyBareNameClusterStillRestores() {
        Cluster legacy = new Cluster();
        legacy.setName("legacy-demo");
        legacy.setStatus(ClusterStatus.ACTIVE);
        legacy.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/legacy-demo");
        clusterStore().putForAccount(ACCOUNT, "legacy-demo", legacy);

        Cluster restored = eastService.describeCluster("legacy-demo");

        assertEquals("legacy-demo", restored.getName());
        assertEquals(legacy.getArn(), restored.getArn());
        assertTrue(clusterStore().getForAccount(ACCOUNT, "legacy-demo").isEmpty());
        assertTrue(clusterStore().getForAccount(ACCOUNT, EAST + "/legacy-demo").isPresent());

        assertThrows(AwsException.class, () -> eastService.createCluster(clusterRequest("legacy-demo")));
        Cluster west = westService.createCluster(clusterRequest("legacy-demo"));
        assertTrue(west.getArn().contains(WEST), west.getArn());
    }

    @Test
    void oidcKeysAreDistinctPerRegion() {
        eastService.createCluster(clusterRequest("oidc-name"));
        westService.createCluster(clusterRequest("oidc-name"));

        Optional<ClusterOidcKey> eastKey = eastOidc.findKeyByCluster(EAST, "oidc-name");
        Optional<ClusterOidcKey> westKey = westOidc.findKeyByCluster(WEST, "oidc-name");

        assertTrue(eastKey.isPresent());
        assertTrue(westKey.isPresent());
        assertNotEquals(eastKey.get().getIssuer(), westKey.get().getIssuer());
        assertTrue(eastKey.get().getIssuer().contains(EAST), eastKey.get().getIssuer());
        assertTrue(westKey.get().getIssuer().contains(WEST), westKey.get().getIssuer());
    }

    private CreateClusterRequest clusterRequest(String name) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-role");
        return request;
    }

    private CreateNodeGroupRequest nodeGroupRequest(String name) {
        CreateNodeGroupRequest request = new CreateNodeGroupRequest();
        request.setNodegroupName(name);
        request.setNodeRole("arn:aws:iam::" + ACCOUNT + ":role/role-name");
        request.setSubnets(List.of("subnet-0e2907431c9988b72", "subnet-04ad87f71c6e5ab4d"));
        return request;
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<Cluster> clusterStore() {
        return (AccountAwareStorageBackend<Cluster>) stores.get("eks-clusters.json");
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "enabled" -> true;
                    case "mock" -> true;
                    case "keepRunningOnShutdown" -> false;
                    case "apiServerBasePort" -> 6500;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "eks" -> eksConfig;
                    default -> defaultValue(method);
                });
        return proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
            case "services" -> servicesConfig;
            case "defaultRegion" -> EAST;
            case "defaultAccountId" -> ACCOUNT;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == String.class) {
            return "";
        }
        return null;
    }
}
