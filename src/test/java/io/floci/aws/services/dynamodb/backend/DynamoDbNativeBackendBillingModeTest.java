package io.floci.aws.services.dynamodb.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.ProvisionedThroughput;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native backend's billing-mode create: the mode the provisioner hands over reaches the
 * stored table, with the throughput the API applies for each mode.
 */
class DynamoDbNativeBackendBillingModeTest {

    private static final Scope SCOPE = new Scope("000000000000", "us-east-1");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbService service = mock(DynamoDbService.class);
    private final NativeDynamoDbBackend backend =
            new NativeDynamoDbBackend(null, null, null, service, null, null, mapper);

    private TableDefinition storedTable() {
        TableDefinition table = new TableDefinition();
        table.setTableName("orders");
        table.setTableArn("arn:aws:dynamodb:us-east-1:000000000000:table/orders");
        // The service builds real tables with the 5/5 default; the mock returns the same.
        table.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        return table;
    }

    private void stubCreateReturningTable(TableDefinition table) {
        when(service.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyString())).thenReturn(table);
    }

    private TableDefinition persistedTable() {
        ArgumentCaptor<TableDefinition> captor = ArgumentCaptor.forClass(TableDefinition.class);
        verify(service).persistTable(eq("orders"), captor.capture(), eq("us-east-1"));
        return captor.getValue();
    }

    @Test
    void payPerRequestTableIsStoredWithNoThroughput() {
        TableDefinition table = storedTable();
        stubCreateReturningTable(table);

        TableDefinition created = backend.createTable(SCOPE, "orders",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                null, null, "PAY_PER_REQUEST", List.of(), List.of());

        verify(service).createTable(eq("orders"), anyList(), anyList(), eq(null), eq(null), anyList(),
                anyList(), eq("us-east-1"));
        assertEquals("PAY_PER_REQUEST", created.getBillingMode());
        assertEquals(0L, created.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, created.getProvisionedThroughput().getWriteCapacityUnits());
        TableDefinition persisted = persistedTable();
        assertEquals("PAY_PER_REQUEST", persisted.getBillingMode());
        assertEquals(0L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, persisted.getProvisionedThroughput().getWriteCapacityUnits());
    }

    @Test
    void provisionedTableWithThroughputIsStoredAsProvisioned() {
        TableDefinition table = storedTable();
        stubCreateReturningTable(table);

        TableDefinition created = backend.createTable(SCOPE, "orders",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                10L, 20L, "PROVISIONED", List.of(), List.of());

        verify(service).createTable(eq("orders"), anyList(), anyList(), eq(10L), eq(20L), anyList(),
                anyList(), eq("us-east-1"));
        assertEquals("PROVISIONED", created.getBillingMode());
        assertEquals(10L, created.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(20L, created.getProvisionedThroughput().getWriteCapacityUnits());
        TableDefinition persisted = persistedTable();
        assertEquals("PROVISIONED", persisted.getBillingMode());
        assertEquals(10L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(20L, persisted.getProvisionedThroughput().getWriteCapacityUnits());
    }

    @Test
    void nullBillingModeDefaultsToProvisioned() {
        TableDefinition table = storedTable();
        stubCreateReturningTable(table);

        TableDefinition created = backend.createTable(SCOPE, "orders",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                null, null, null, List.of(), List.of());

        verify(service).createTable(eq("orders"), anyList(), anyList(), eq(null), eq(null), anyList(),
                anyList(), eq("us-east-1"));
        assertEquals("PROVISIONED", created.getBillingMode());
        assertEquals(5L, created.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(5L, created.getProvisionedThroughput().getWriteCapacityUnits());
        assertEquals("PROVISIONED", persistedTable().getBillingMode());
    }
}
