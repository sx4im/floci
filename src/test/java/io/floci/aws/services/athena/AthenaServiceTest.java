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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
        GlueViewDdlBuilder ddlBuilder = mock(GlueViewDdlBuilder.class);

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
    void getQueryResultsReportsDuckDbColumnTypes() throws Exception {
        List<Map<String, Object>> describeRows = List.of(
                Map.of("column_name", "n", "column_type", "BIGINT"),
                Map.of("column_name", "d", "column_type", "DOUBLE"),
                Map.of("column_name", "b", "column_type", "BOOLEAN"),
                Map.of("column_name", "dt", "column_type", "DATE"));
        when(duckClient.query(anyString(), any())).thenReturn(describeRows);

        byte[] csv = "n,d,b,dt\n1,1.5,true,2026-01-01\n".getBytes(StandardCharsets.UTF_8);
        when(s3Service.listObjects(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(new S3Object("floci-athena-results", "results/q.csv", csv, "text/csv")));
        when(s3Service.getObject(anyString(), anyString()))
                .thenReturn(new S3Object("floci-athena-results", "results/q.csv", csv, "text/csv"));

        String id = service.startQueryExecution(
                "SELECT CAST(1 AS BIGINT) AS n, CAST(1.5 AS DOUBLE) AS d, true AS b, DATE '2026-01-01' AS dt",
                "primary", null, null);
        awaitState(id, QueryExecutionState.SUCCEEDED);

        ResultSet results = service.getQueryResults(id);
        List<ResultSet.ColumnInfo> columns = results.getMetadata().getColumnInfo();
        assertEquals(List.of("n", "d", "b", "dt"),
                columns.stream().map(ResultSet.ColumnInfo::getName).toList());
        assertEquals(List.of("bigint", "double", "boolean", "date"),
                columns.stream().map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void getQueryResultsFallsBackToVarcharWhenDescribeFails() throws Exception {
        when(duckClient.query(anyString(), any())).thenThrow(new RuntimeException("duck down"));

        byte[] csv = "n,d\n1,1.5\n".getBytes(StandardCharsets.UTF_8);
        when(s3Service.listObjects(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(new S3Object("floci-athena-results", "results/q.csv", csv, "text/csv")));
        when(s3Service.getObject(anyString(), anyString()))
                .thenReturn(new S3Object("floci-athena-results", "results/q.csv", csv, "text/csv"));

        String id = service.startQueryExecution("SELECT 1 AS n, 1.5 AS d", "primary", null, null);
        awaitState(id, QueryExecutionState.SUCCEEDED);

        ResultSet results = service.getQueryResults(id);
        assertEquals(List.of("varchar", "varchar"),
                results.getMetadata().getColumnInfo().stream().map(ResultSet.ColumnInfo::getType).toList());
    }

    @Test
    void duckDbTypeToAthenaMapsScalars() {
        assertEquals("bigint", AthenaService.duckDbTypeToAthena("BIGINT"));
        assertEquals("integer", AthenaService.duckDbTypeToAthena("INTEGER"));
        assertEquals("smallint", AthenaService.duckDbTypeToAthena("SMALLINT"));
        assertEquals("tinyint", AthenaService.duckDbTypeToAthena("TINYINT"));
        assertEquals("double", AthenaService.duckDbTypeToAthena("DOUBLE"));
        assertEquals("float", AthenaService.duckDbTypeToAthena("FLOAT"));
        assertEquals("boolean", AthenaService.duckDbTypeToAthena("BOOLEAN"));
        assertEquals("date", AthenaService.duckDbTypeToAthena("DATE"));
        assertEquals("timestamp", AthenaService.duckDbTypeToAthena("TIMESTAMP"));
        assertEquals("timestamp with time zone", AthenaService.duckDbTypeToAthena("TIMESTAMP WITH TIME ZONE"));
        assertEquals("decimal(10,2)", AthenaService.duckDbTypeToAthena("DECIMAL(10,2)"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("VARCHAR"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("VARCHAR(10)"));
        assertEquals("char", AthenaService.duckDbTypeToAthena("CHAR(5)"));
        assertEquals("varbinary", AthenaService.duckDbTypeToAthena("BLOB"));
        assertEquals("bigint", AthenaService.duckDbTypeToAthena("bigint"));
    }

    @Test
    void duckDbTypeToAthenaFallsBackToVarcharForComplexAndUnknownTypes() {
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("STRUCT(a INTEGER)"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("INTEGER[]"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("MAP(VARCHAR, INTEGER)"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("UUID"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("SOME_FUTURE_TYPE"));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena(null));
        assertEquals("varchar", AthenaService.duckDbTypeToAthena("  "));
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
