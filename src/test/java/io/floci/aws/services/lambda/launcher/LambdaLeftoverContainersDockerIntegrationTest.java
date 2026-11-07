package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Floci that ends without a graceful shutdown leaves its warm Lambda containers on the daemon:
 * each loses its Runtime API connection, exits, and nothing removes it, so the next run must
 * sweep them at startup. The sweep recognises them by the owner label a Lambda container is
 * created with, so a container of another Floci sharing the daemon stays.
 *
 * <p>Runs against a real Docker daemon only; without one the test assumes away.
 */
@QuarkusTest
class LambdaLeftoverContainersDockerIntegrationTest {

    private static final String BUSYBOX_IMAGE = TestImages.BUSYBOX;

    @Inject
    ContainerLauncher containerLauncher;

    @Inject
    EmulatorConfig config;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for the Lambda leftover container test");
    }

    @Test
    void theStartupSweepRemovesPreviousRunLambdaContainersAndKeepsAnotherFlocisContainers() {
        String foreignId = null;
        String previousRunId = null;
        try {
            foreignId = createLambdaContainer(Map.of(ContainerStorageHelper.OWNER_LABEL, "another-floci/4566"));
            // Still running, as by a run killed moments before this one started: the sweep
            // must remove it anyway, or it exits on its own and stays as Exited forever.
            previousRunId = createLambdaContainer(Map.of(ContainerStorageHelper.OWNER_LABEL,
                    ContainerStorageHelper.ownerIdentity(config)));

            assertTrue(containerLauncher.removeLeftoverContainers(), "the sweep must succeed");
            assertFalse(exists(previousRunId), "a previous run's container must be removed");
            assertTrue(exists(foreignId), "another Floci's container must stay");
        } finally {
            for (String containerId : new String[] {foreignId, previousRunId}) {
                if (containerId != null && exists(containerId)) {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                }
            }
        }
    }

    /** A Lambda container carrying {@code ownership}, as another Floci or another run creates. */
    private String createLambdaContainer(Map<String, String> ownership) {
        Map<String, String> labels = new HashMap<>(ownership);
        labels.put("io.floci.service", "lambda");
        CreateContainerResponse created = dockerClient.createContainerCmd(BUSYBOX_IMAGE)
                .withName(unique("floci-other-lambda"))
                .withCmd("sleep", "120")
                .withLabels(labels)
                .exec();
        dockerClient.startContainerCmd(created.getId()).exec();
        return created.getId();
    }

    private boolean exists(String containerId) {
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
