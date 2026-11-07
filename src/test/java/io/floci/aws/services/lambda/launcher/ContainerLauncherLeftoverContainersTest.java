package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.lambda.LambdaLayerService;
import io.floci.aws.services.lambda.runtime.RuntimeApiServerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A Floci that ends without a graceful shutdown leaves its warm Lambda containers on the daemon:
 * each loses its Runtime API connection, exits, and nothing removes it, so the next run must
 * sweep them at startup. The sweep recognises them by the owner label a Lambda container is
 * created with, so a container of another Floci sharing the daemon stays.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLauncherLeftoverContainersTest {

    private static final String OWNER = "4566";

    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerBuilder containerBuilder;
    @Mock ContainerLogStreamer logStreamer;
    @Mock ImageResolver imageResolver;
    @Mock RuntimeApiServerFactory runtimeApiServerFactory;
    @Mock DockerHostResolver dockerHostResolver;
    @Mock EmulatorConfig config;
    @Mock EmulatorConfig.ServicesConfig services;
    @Mock EmulatorConfig.LambdaServiceConfig lambda;
    @Mock EmulatorConfig.DockerConfig docker;
    @Mock EcrRegistryManager ecrRegistryManager;
    @Mock LambdaLayerService layerService;
    @Mock LaunchedContainerAwsEnv awsEnv;
    @Mock LambdaExecutionRoleCredentials executionRoleCredentials;
    @Mock Ec2Service ec2Service;
    @Mock DockerClient dockerClient;
    @Mock ListContainersCmd listCmd;

    ContainerLauncher launcher;
    /** The containers a faithful Docker label filter would return for each captured filter. */
    final List<Container> candidates = new ArrayList<>();
    final List<Map<String, String>> capturedFilters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(config.docker()).thenReturn(docker);
        when(config.port()).thenReturn(4566);
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(anyMap())).thenAnswer(invocation -> {
            capturedFilters.add(invocation.getArgument(0));
            return listCmd;
        });
        when(listCmd.exec()).thenAnswer(invocation -> candidates.stream()
                .filter(candidate -> matchesFilter(candidate.getLabels(),
                        capturedFilters.get(capturedFilters.size() - 1)))
                .toList());

        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager, layerService,
                awsEnv, executionRoleCredentials, ec2Service);
    }

    @Test
    void removesPreviousRunContainersWhateverTheirState() {
        candidates.add(container("exited", "Exited (1)", lambdaLabels()));
        candidates.add(container("running", "Up 3 seconds", lambdaLabels()));

        assertTrue(launcher.removeLeftoverContainers());

        verify(lifecycleManager).removeIfExistsStrict("exited", true);
        verify(lifecycleManager).removeIfExistsStrict("running", true);
        // The sweep asks Docker for this deployment's Lambda containers only, under both
        // spellings of the owner label.
        assertEquals(List.of(
                Map.of("io.floci.service", "lambda", "io.floci.owner", OWNER),
                Map.of("io.floci.service", "lambda", "floci_owner_port", OWNER)),
                capturedFilters);
    }

    @Test
    void keepsAnotherDeploymentsContainers() {
        candidates.add(container("foreign", "Exited (1)", Map.of("io.floci", "aws",
                "io.floci.service", "lambda", "io.floci.resource-id", "other-fn",
                "io.floci.owner", "4567")));

        assertTrue(launcher.removeLeftoverContainers());

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString(), anyBoolean());
    }

    @Test
    void keepsContainersWhoseOwnerAliasDisagrees() {
        candidates.add(container("tampered", "Exited (1)", Map.of("io.floci.service", "lambda",
                "io.floci.owner", OWNER, "floci_owner_port", "4567")));

        assertTrue(launcher.removeLeftoverContainers());

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString(), anyBoolean());
    }

    @Test
    void removesContainersLabelledWithOnlyTheLegacyOwnerKey() {
        candidates.add(container("legacy", "Exited (1)", Map.of("io.floci.service", "lambda",
                "floci_owner_port", OWNER)));

        assertTrue(launcher.removeLeftoverContainers());

        verify(lifecycleManager).removeIfExistsStrict("legacy", true);
    }

    @Test
    void listFailureReturnsFalse() {
        when(listCmd.exec()).thenThrow(new RuntimeException("daemon down"));

        assertFalse(launcher.removeLeftoverContainers());

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString(), anyBoolean());
    }

    @Test
    void removalFailureOnOneContainerStillRemovesTheOther() {
        candidates.add(container("bad", "Exited (1)", lambdaLabels()));
        candidates.add(container("good", "Exited (1)", lambdaLabels()));
        doThrow(new IllegalStateException("daemon refused")).when(lifecycleManager)
                .removeIfExistsStrict("bad", true);

        assertFalse(launcher.removeLeftoverContainers());

        verify(lifecycleManager).removeIfExistsStrict("good", true);
    }

    @Test
    void initSkipsTheSweepWhenReconcileIsOff() {
        when(lambda.reconcileContainersOnStartup()).thenReturn(false);

        try {
            launcher.init();
            verifyNoInteractions(dockerClient);
        } finally {
            launcher.shutdown();
        }
    }

    @Test
    void initSweepsWhenReconcileIsOn() {
        when(lambda.reconcileContainersOnStartup()).thenReturn(true);

        try {
            launcher.init();
            verify(listCmd, atLeastOnce()).exec();
        } finally {
            launcher.shutdown();
        }
    }

    /** Whether Docker's label filter would list a container carrying {@code labels}. */
    private static boolean matchesFilter(Map<String, String> labels, Map<String, String> filter) {
        if (labels == null || filter == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            if (!entry.getValue().equals(labels.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> lambdaLabels() {
        return Map.of("io.floci", "aws", "io.floci.service", "lambda",
                "io.floci.resource-id", "orphan-demo", "io.floci.owner", OWNER);
    }

    private static Container container(String id, String status, Map<String, String> labels) {
        // Lenient: the sweep never reads status, and a container the listing filter excludes
        // never has any of its stubbings invoked, which strict stubs would fail on.
        Container container = mock(Container.class);
        lenient().when(container.getId()).thenReturn(id);
        lenient().when(container.getStatus()).thenReturn(status);
        lenient().when(container.getLabels()).thenReturn(labels);
        lenient().when(container.getNames()).thenReturn(new String[] {"/" + id});
        return container;
    }
}
