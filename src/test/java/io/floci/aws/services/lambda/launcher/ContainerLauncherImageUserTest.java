package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.lambda.LambdaLayerService;
import io.floci.aws.services.lambda.model.LambdaFileSystemConfig;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;
import io.floci.aws.services.lambda.runtime.RuntimeApiServerFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Image functions run as a fixed non-root uid by default (AWS never runs an
 * image as the image's own USER), and launch fails fast with
 * {@code Runtime.InvalidEntrypoint} when that uid cannot enter the image's
 * working directory (#7979).
 */
@ExtendWith(MockitoExtension.class)
class ContainerLauncherImageUserTest {

    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerLogStreamer logStreamer;
    @Mock ImageResolver imageResolver;
    @Mock RuntimeApiServerFactory runtimeApiServerFactory;
    @Mock DockerHostResolver dockerHostResolver;
    @Mock EmulatorConfig config;
    @Mock EcrRegistryManager ecrRegistryManager;
    @Mock RuntimeApiServer runtimeApiServer;
    @Mock DockerClient dockerClient;
    @Mock LambdaExecutionRoleCredentials executionRoleCredentials;
    @Mock LambdaLayerService layerService;
    @Mock Ec2Service ec2Service;
    @Mock LaunchedContainerAwsEnv awsEnv;

    @TempDir
    Path tempDir;

    EmulatorConfig.ServicesConfig services;
    EmulatorConfig.LambdaServiceConfig lambda;
    EmulatorConfig.DockerConfig docker;
    EmulatorConfig.StorageConfig storage;
    EmulatorConfig.EfsSharingConfig efs;
    ContainerBuilder builder;
    ContainerLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        services = mock(EmulatorConfig.ServicesConfig.class);
        lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        docker = mock(EmulatorConfig.DockerConfig.class);
        storage = mock(EmulatorConfig.StorageConfig.class);
        efs = mock(EmulatorConfig.EfsSharingConfig.class);

        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.lambda()).thenReturn(lambda);
        lenient().when(lambda.reconcileContainersOnStartup()).thenReturn(true);
        lenient().when(lambda.dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(lambda.extraHosts()).thenReturn(Optional.empty());
        lenient().when(lambda.awsConfigPath()).thenReturn(Optional.empty());
        lenient().when(lambda.dockerFlags()).thenReturn(Optional.empty());
        lenient().when(lambda.honourArchitectures()).thenReturn(false);
        lenient().when(lambda.codeVolumePopulateConcurrency()).thenReturn(Optional.empty());
        lenient().when(lambda.containerNamePrefix()).thenReturn(Optional.empty());
        lenient().when(config.docker()).thenReturn(docker);
        lenient().when(docker.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4566");
        lenient().when(config.port()).thenReturn(4566);
        lenient().when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        lenient().when(config.defaultRegion()).thenReturn("us-east-1");
        lenient().when(config.defaultAccountId()).thenReturn("000000000000");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.storage()).thenReturn(storage);
        lenient().when(storage.persistentPath()).thenReturn(tempDir.toString());
        lenient().when(storage.efs()).thenReturn(efs);
        lenient().when(efs.ownerUid()).thenReturn(OptionalInt.empty());
        lenient().when(efs.ownerGid()).thenReturn(OptionalInt.empty());
        lenient().when(efs.rootPermissions()).thenReturn(Optional.empty());
        lenient().when(efs.initImage()).thenReturn("busybox:stable");
        lenient().when(efs.mountUser()).thenReturn(Optional.empty());
        lenient().when(efs.mountGroupAdd()).thenReturn(OptionalInt.empty());

        lenient().when(ecrRegistryManager.rewriteImageUri(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(executionRoleCredentials.forFunction(any())).thenReturn(Optional.empty());
        lenient().when(awsEnv.flociEndpoint(anyBoolean())).thenReturn("http://localhost:4566");
        lenient().when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), any()))
                .thenReturn(List.of());
        lenient().when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        lenient().when(runtimeApiServer.getPort()).thenReturn(9000);
        lenient().when(runtimeApiServer.stop()).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(runtimeApiServer.close()).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(runtimeApiServer.awaitExtensionsReady(anyLong())).thenReturn(true);
        lenient().when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");
        lenient().when(lifecycleManager.create(any())).thenReturn("container-123");
        lenient().when(lifecycleManager.create(any(), anyString())).thenReturn("container-123");
        lenient().when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        // Stub the Docker copy chain so code archive copies do not throw when
        // the mock DockerClient is used. Each invocation returns a fresh mock
        // that drains the tar InputStream on exec(), so the background
        // PipedOutputStream writer thread cannot block when the pipe fills.
        lenient().when(dockerClient.copyArchiveToContainerCmd(any())).thenAnswer(inv -> {
            CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class);
            final InputStream[] captured = {null};
            when(cmd.withRemotePath(any())).thenAnswer(pathInv -> cmd);
            when(cmd.withTarInputStream(any())).thenAnswer(streamInv -> {
                captured[0] = streamInv.getArgument(0);
                return cmd;
            });
            doAnswer(execInv -> {
                if (captured[0] != null) {
                    try {
                        captured[0].transferTo(OutputStream.nullOutputStream());
                    } catch (Exception ignored) {
                        // The drain only keeps the background tar writer from
                        // blocking; a failure here means the copy already failed.
                    }
                }
                return null;
            }).when(cmd).exec();
            return cmd;
        });

        builder = new ContainerBuilder();
        launcher = new ContainerLauncher(builder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager,
                layerService, awsEnv, executionRoleCredentials, ec2Service);
    }

    private static LambdaFunction imageFunction() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("image-function");
        fn.setAccountId("222233334444");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:222233334444:function:image-function");
        fn.setPackageType("Image");
        fn.setImageUri("unit-lambda:latest");
        return fn;
    }

    /**
     * Stubs the container inspect the workdir check reads the effective user and workdir from.
     * Lenient: the unfixed launcher never calls inspect, so a strict stub would fail the red
     * run for a stubbing reason instead of the missing behavior; the assertions carry the test.
     */
    private void stubContainerInspect(String user, String workingDir) {
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        ContainerConfig containerConfig = mock(ContainerConfig.class);
        lenient().when(response.getConfig()).thenReturn(containerConfig);
        lenient().when(containerConfig.getUser()).thenReturn(user);
        lenient().when(containerConfig.getWorkingDir()).thenReturn(workingDir);
        lenient().when(cmd.exec()).thenReturn(response);
        lenient().when(dockerClient.inspectContainerCmd("container-123")).thenReturn(cmd);
    }

    /** Stubs the Docker archive probe for one working-directory ancestor. Lenient, as above. */
    private void stubWorkdirProbe(String directory, String entryName, int mode, int uid, int gid)
            throws IOException {
        byte[] tar = tarOfDirEntry(entryName, mode, uid, gid);
        CopyArchiveFromContainerCmd cmd = mock(CopyArchiveFromContainerCmd.class);
        lenient().when(cmd.exec()).thenReturn(new ByteArrayInputStream(tar));
        lenient().when(dockerClient.copyArchiveFromContainerCmd("container-123", directory)).thenReturn(cmd);
    }

    /** Stubs a probe that returns an archive without the directory entry (fail-open). Lenient, as above. */
    private void stubEmptyProbe(String directory) {
        CopyArchiveFromContainerCmd cmd = mock(CopyArchiveFromContainerCmd.class);
        lenient().when(cmd.exec()).thenReturn(new ByteArrayInputStream(new byte[0]));
        lenient().when(dockerClient.copyArchiveFromContainerCmd("container-123", directory)).thenReturn(cmd);
    }

    /** A tar holding a single directory entry, as the Docker archive API returns for a directory. */
    private static byte[] tarOfDirEntry(String entryName, int mode, int uid, int gid) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName.endsWith("/") ? entryName : entryName + "/");
            entry.setMode(mode);
            entry.setUserId(uid);
            entry.setGroupId(gid);
            entry.setSize(0);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void imageFunctionRunsAsFixedNonRootUser() throws Exception {
        stubContainerInspect("65534:65534", "/app");
        stubWorkdirProbe("/app", "app/", 040755, 0, 0);

        launcher.launch(imageFunction());

        assertEquals("65534:65534", builder.lastSpec().user());
    }

    @Test
    void imageFunctionKeepsExplicitDockerUser() throws Exception {
        when(lambda.dockerFlags()).thenReturn(Optional.of("--user 1000:1000"));
        stubContainerInspect("1000:1000", "/app");
        stubWorkdirProbe("/app", "app/", 040755, 0, 0);

        launcher.launch(imageFunction());

        assertEquals("1000:1000", builder.lastSpec().user());
        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void imageFunctionKeepsEfsMountUser() throws Exception {
        LambdaFileSystemConfig fsConfig = new LambdaFileSystemConfig();
        fsConfig.setArn("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0");
        fsConfig.setLocalMountPath("/mnt/efs");
        LambdaFunction fn = imageFunction();
        fn.setFileSystemConfigs(List.of(fsConfig));
        when(efs.mountUser()).thenReturn(Optional.of("1001"));
        stubContainerInspect("1001", "/");

        launcher.launch(fn);

        assertEquals("1001", builder.lastSpec().user());
    }

    @Test
    void imageFunctionWithInaccessibleWorkdirFailsInit() throws Exception {
        stubContainerInspect("65534:65534", "/home/nonroot");
        stubWorkdirProbe("/home", "home/", 040755, 0, 0);
        stubWorkdirProbe("/home/nonroot", "nonroot/", 040700, 65532, 65532);

        LambdaFunction fn = imageFunction();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> launcher.launch(fn));

        assertTrue(thrown.getMessage().contains("Runtime.InvalidEntrypoint"),
                "launch must fail as Runtime.InvalidEntrypoint, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("/home/nonroot"),
                "the failure must name the blocking directory, was: " + thrown.getMessage());
        verify(lifecycleManager, times(0)).startCreated(anyString(), any(ContainerSpec.class));
        verify(lifecycleManager).stopAndRemoveStrict(eq("container-123"), isNull());
    }

    @Test
    void imageFunctionWithAccessibleWorkdirStarts() throws Exception {
        stubContainerInspect("65534:65534", "/home/nonroot");
        stubWorkdirProbe("/home", "home/", 040755, 0, 0);
        stubWorkdirProbe("/home/nonroot", "nonroot/", 040755, 65532, 65532);

        launcher.launch(imageFunction());

        assertEquals("65534:65534", builder.lastSpec().user());
        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void imageFunctionProbeFailureFailsOpen() throws Exception {
        stubContainerInspect("65534:65534", "/home/nonroot");
        stubEmptyProbe("/home/nonroot");

        launcher.launch(imageFunction());

        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void imageFunctionInspectFailureFallsBackToSpec() throws Exception {
        LambdaFunction fn = imageFunction();
        fn.setImageConfigWorkingDirectory("/srv/app");

        launcher.launch(fn);

        assertEquals("65534:65534", builder.lastSpec().user());
        assertEquals("/srv/app", builder.lastSpec().workingDir());
        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void imageFunctionUsesImageConfigWorkingDirectory() throws Exception {
        LambdaFunction fn = imageFunction();
        fn.setImageConfigWorkingDirectory("/srv/app");
        stubContainerInspect("65534:65534", "/srv/app");
        stubWorkdirProbe("/srv", "srv/", 040755, 0, 0);
        stubWorkdirProbe("/srv/app", "app/", 040755, 0, 0);

        launcher.launch(fn);

        assertEquals("/srv/app", builder.lastSpec().workingDir());
        assertEquals("65534:65534", builder.lastSpec().user());
    }

    @Test
    void imageFunctionWithNamedUserSkipsCheck() throws Exception {
        when(lambda.dockerFlags()).thenReturn(Optional.of("--user nonroot"));
        stubContainerInspect("nonroot", "/home/nonroot");

        launcher.launch(imageFunction());

        assertEquals("nonroot", builder.lastSpec().user());
        verify(dockerClient, times(0)).copyArchiveFromContainerCmd(eq("container-123"), eq("/home"));
        verify(dockerClient, times(0)).copyArchiveFromContainerCmd(eq("container-123"), eq("/home/nonroot"));
        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void imageFunctionWithBareUidAndGroupOnlyDirFailsOpen() throws Exception {
        when(lambda.dockerFlags()).thenReturn(Optional.of("--user 1000"));
        stubContainerInspect("1000", "/data");
        stubWorkdirProbe("/data", "data/", 040750, 0, 1000);

        launcher.launch(imageFunction());

        assertEquals("1000", builder.lastSpec().user());
        verify(lifecycleManager, times(1)).startCreated(eq("container-123"), any(ContainerSpec.class));
    }

    @Test
    void zipFunctionKeepsImageUser() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("zip-code"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("zip-function");
        fn.setAccountId("222233334444");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:222233334444:function:zip-function");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        assertNull(builder.lastSpec().user());
    }

    @Test
    void probeBlocksInaccessibleWorkdir() throws Exception {
        ContainerSpec spec = new ContainerSpec("unit-lambda:latest");
        stubContainerInspect("65534:65534", "/home/nonroot");
        stubWorkdirProbe("/home", "home/", 040755, 0, 0);
        stubWorkdirProbe("/home/nonroot", "nonroot/", 040700, 65532, 65532);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> launcher
                .verifyImageFunctionWorkingDirectory(dockerClient, "container-123", imageFunction(), spec));

        assertTrue(thrown.getMessage().contains("Runtime.InvalidEntrypoint"),
                "the failure must name Runtime.InvalidEntrypoint, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("0700"),
                "the failure must name the blocking mode, was: " + thrown.getMessage());
    }

    @Test
    void probePassesAccessibleWorkdir() throws Exception {
        ContainerSpec spec = new ContainerSpec("unit-lambda:latest");
        stubContainerInspect("65534:65534", "/home/nonroot");
        stubWorkdirProbe("/home", "home/", 040755, 0, 0);
        stubWorkdirProbe("/home/nonroot", "nonroot/", 040755, 65532, 65532);

        launcher.verifyImageFunctionWorkingDirectory(dockerClient, "container-123", imageFunction(), spec);
    }

    @Test
    void ancestorDirectoriesListsPathBelowRoot() {
        assertEquals(List.of("/home", "/home/nonroot"),
                ContainerLauncher.ancestorDirectories("/home/nonroot"));
        assertEquals(List.of("/app"), ContainerLauncher.ancestorDirectories("/app"));
        assertEquals(List.of("/home", "/home/nonroot"),
                ContainerLauncher.ancestorDirectories("/home/nonroot/"));
        assertEquals(List.of("/app"), ContainerLauncher.ancestorDirectories("app"));
        assertEquals(List.of(), ContainerLauncher.ancestorDirectories("/"));
        assertEquals(List.of(), ContainerLauncher.ancestorDirectories(null));
        assertEquals(List.of(), ContainerLauncher.ancestorDirectories(""));
    }

    @Test
    void canTraverseAsRespectsOwnerGroupOther() {
        assertTrue(ContainerLauncher.canTraverseAs(040755, 0, 0, 65534, Set.of(65534)));
        assertTrue(ContainerLauncher.canTraverseAs(040700, 65534, 65534, 65534, Set.of(65534)));
        assertTrue(ContainerLauncher.canTraverseAs(040750, 0, 65534, 65534, Set.of(65534)));
        assertTrue(ContainerLauncher.canTraverseAs(040750, 0, 999, 65534, Set.of(999)));
        assertEquals(false, ContainerLauncher.canTraverseAs(040700, 65532, 65532, 65534, Set.of(65534)));
        assertEquals(false, ContainerLauncher.canTraverseAs(040750, 0, 999, 65534, Set.of(65534)));
        assertEquals(false, ContainerLauncher.canTraverseAs(040000, 0, 0, 65534, Set.of(65534)));
    }

    @Test
    void parseUserIdHandlesNumericAndNamedForms() {
        assertEquals(List.of(65534, 65534),
                List.of(ContainerLauncher.parseUserId("65534:65534")[0],
                        ContainerLauncher.parseUserId("65534:65534")[1]));
        assertEquals(1000, ContainerLauncher.parseUserId("1000")[0]);
        assertEquals(-1, ContainerLauncher.parseUserId("1000")[1]);
        assertNull(ContainerLauncher.parseUserId("nonroot"));
        assertNull(ContainerLauncher.parseUserId("1000:abc"));
        assertNull(ContainerLauncher.parseUserId("1:2:3"));
        assertNull(ContainerLauncher.parseUserId(null));
        assertNull(ContainerLauncher.parseUserId(""));
    }
}
