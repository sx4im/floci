package io.floci.aws.services.dynamodb;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The billing-mode entry point exists for callers that reach DynamoDB through this service
 * directly, notably CloudFormation provisioning: {@code NativeDynamoDbTableService} sets the
 * mode inline on CreateTable/UpdateTable, and without an equivalent entry point a table
 * created by any other path keeps the provisioned default. Applying the mode must persist,
 * so a later DescribeTable reports it.
 */
class DynamoDbBillingModeServiceTest {

    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
    }

    private TableDefinition createTable() {
        return service.createTable("Billing",
                List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")),
                5L, 5L, "us-east-1");
    }

    @Test
    void payPerRequestSetsBillingModeAndZeroesThroughput() {
        createTable();

        TableDefinition table = service.applyBillingMode("Billing", "PAY_PER_REQUEST", "us-east-1");

        assertEquals("PAY_PER_REQUEST", table.getBillingMode());
        assertEquals(0L, table.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, table.getProvisionedThroughput().getWriteCapacityUnits());
        TableDefinition described = service.describeTable("Billing", "us-east-1");
        assertEquals("PAY_PER_REQUEST", described.getBillingMode());
        assertEquals(0L, described.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, described.getProvisionedThroughput().getWriteCapacityUnits());
    }

    @Test
    void provisionedSetsBillingModeAndKeepsThroughput() {
        createTable();

        TableDefinition table = service.applyBillingMode("Billing", "PROVISIONED", "us-east-1");

        assertEquals("PROVISIONED", table.getBillingMode());
        assertEquals(5L, table.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(5L, table.getProvisionedThroughput().getWriteCapacityUnits());
    }
}
