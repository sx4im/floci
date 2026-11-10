package io.floci.aws.services.athena;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.athena.model.ResultSet;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A {@code StopQueryExecution} must stay terminal. The async worker used to overwrite whatever
 * {@code stopQueryExecution} wrote, so a stopped query later reported {@code SUCCEEDED} or
 * {@code FAILED}. These tests drive the worker with a stubbed duck client.
 */
class AthenaServiceTest {

    private Vertx vertx;
    private FlociDuckClient duckClient;
    private S3Service s3Service;
    private GlueViewDdlBuilder ddlBuilder;
    private AthenaService service;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        duckClient = mock(FlociDuckClient.class);

        StorageFactory storageFactory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.inMemory("000000000000"))
                .when(storageFactory).create(anyString(), anyString(), any());

        GlueService glueService = mock(GlueService.class);
        when(glueService.getTables(anyString())).thenReturn(List.of());
        s3Service = mock(S3Service.class);
        ddlBuilder = mock(GlueViewDdlBuilder.class);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AthenaServiceConfig athena = mock(EmulatorConfig.AthenaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.athena()).thenReturn(athena);
        when(athena.mock()).thenReturn(false);

        service = new AthenaService(storageFactory, duckClient, glueService, s3Service,
                config, vertx, ddlBuilder, mock(RegionResolver.class));
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void stopDuringExecutionKeepsCancellationTerminal() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            finished.countDown();
            return null;
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(id);
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState());

        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState(),
                "the worker must not overwrite a cancellation");
    }

    @Test
    void stopDuringExecutionStaysTerminalWhenTheWorkerFails() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            finished.countDown();
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");

        service.stopQueryExecution(id);
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker never finished");
        assertEquals(QueryExecutionState.CANCELLED, service.getQueryExecution(id).getStatus().getState(),
                "a worker failure must not overwrite a cancellation");
    }

    @Test
    void stopAfterCompletionLeavesTheExecutionSucceeded() throws Exception {
        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.SUCCEEDED);
        service.stopQueryExecution(id);

        assertEquals(QueryExecutionState.SUCCEEDED, service.getQueryExecution(id).getStatus().getState(),
                "a late stop must not cancel a finished query");
    }

    @Test
    void stopAfterFailureLeavesTheExecutionFailed() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("duck failed");
        }).when(duckClient).execute(anyString(), any(), any());

        String id = service.startQueryExecution("SELECT 1", "primary", null, null);

        awaitState(id, QueryExecutionState.FAILED);
        service.stopQueryExecution(id);

        assertEquals(QueryExecutionState.FAILED, service.getQueryExecution(id).getStatus().getState(),
                "a late stop must not cancel a failed query");
    }

    @Test
    void getQueryResultsReportsRealColumnTypes() throws Exception {
        when(ddlBuilder.build(any())).thenReturn("");
        when(duckClient.query(anyString(), any())).thenReturn(List.of(
                Map.of("column_name", "n", "column_type", "BIGINT"),
                Map.of("column_name", "d", "column_type", "DOUBLE"),
                Map.of("column_name", "b", "column_type", "BOOLEAN"),
                Map.of("column_name", "dt", "column_type", "DATE")));
        stubResultCsv("n,d,b,dt\n1,1.5,true,2026-01-01\n");

        String id = service.startQueryExecution(
                "SELECT CAST(1 AS BIGINT) AS n, CAST(1.5 AS DOUBLE) AS d, true AS b, DATE '2026-01-01' AS dt",
                "primary", null, null);
        awaitState(id, QueryExecutionState.SUCCEEDED);

        ResultSet resultSet = service.getQueryResults(id);
        List<ResultSet.ColumnInfo> columns = resultSet.getMetadata().getColumnInfo();
        assertEquals(List.of("n", "d", "b", "dt"),
                columns.stream().map(ResultSet.ColumnInfo::getName).toList());
        assertEquals(List.of("bigint", "double", "boolean", "date"),
                columns.stream().map(ResultSet.ColumnInfo::getType).toList());
        verify(duckClient).query(argThat(sql -> sql.startsWith("DESCRIBE ")), any());
    }

    @Test
    void getQueryResultsFallsBackToVarcharWhenDescribeFails() throws Exception {
        when(duckClient.query(anyString(), any())).thenThrow(new IllegalStateException("duck down"));
        stubResultCsv("n\n1\n");

        String id = service.startQueryExecution("SELECT 1 AS n", "primary", null, null);
        awaitState(id, QueryExecutionState.SUCCEEDED);

        ResultSet resultSet = service.getQueryResults(id);
        assertEquals(List.of("varchar"), resultSet.getMetadata().getColumnInfo().stream()
                .map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void getQueryResultsDoesNotDescribeShowStatements() throws Exception {
        stubResultCsv("table_name\nfoo\n");

        String id = service.startQueryExecution("SHOW TABLES", "primary", null, null);
        awaitState(id, QueryExecutionState.SUCCEEDED);

        ResultSet resultSet = service.getQueryResults(id);
        assertEquals(List.of("varchar"), resultSet.getMetadata().getColumnInfo().stream()
                .map(ResultSet.ColumnInfo::getType).toList());
        verify(duckClient, never()).query(anyString(), any());
    }

    private void stubResultCsv(String csv) {
        byte[] data = csv.getBytes(StandardCharsets.UTF_8);
        S3Object object = new S3Object("floci-athena-results", "results/query.csv", data, "text/csv");
        when(s3Service.listObjects(anyString(), anyString(), any(), anyInt())).thenReturn(List.of(object));
        when(s3Service.getObject(anyString(), anyString())).thenReturn(object);
    }

    private void awaitState(String id, QueryExecutionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (service.getQueryExecution(id).getStatus().getState() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, service.getQueryExecution(id).getStatus().getState());
    }
}
