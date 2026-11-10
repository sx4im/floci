package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.DynamoDbCfnProvisioner;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.backend.NativeDynamoDbBackend;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A template that declares {@code BillingMode: PAY_PER_REQUEST} on
 * {@code AWS::DynamoDB::Table} must get an on-demand table.
 *
 * <p>{@code DynamoDbCfnProvisioner} never read {@code BillingMode} (or
 * {@code ProvisionedThroughput}) from the resource properties. It called
 * {@code createTable} with null capacities, so the table got the default provisioned
 * settings: no {@code BillingModeSummary} and 5/5 throughput. The DynamoDB API path
 * on the same runtime already applies the mode correctly.
 *
 * <p>Built directly on the provisioner, as {@code DynamoDbCfnProvisionerTest} does,
 * rather than through the fixture: the fixture wires every provisioner in the
 * registry, which the standalone harness cannot compile.
 */
class DynamoDbBillingModeCfnProvisionerTest {

    private static final String TABLE = "billing-repro";
    private static final String TABLE_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE;

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private DynamoDbCfnProvisioner provisioner;

    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        NativeDynamoDbBackend backend =
                new NativeDynamoDbBackend(null, null, null, dynamoDbService, null, null, mapper);
        provisioner = new DynamoDbCfnProvisioner(new DynamoDbFacade(backend, backend,
                new RegionResolver("us-east-1", "000000000000")));

        TableDefinition created = new TableDefinition();
        created.setTableName(TABLE);
        created.setTableArn(TABLE_ARN);
        when(dynamoDbService.createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString())).thenReturn(created);
    }

    /** The table the service reports once its billing mode is on-demand. */
    private TableDefinition payPerRequest() {
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE);
        table.setTableArn(TABLE_ARN);
        table.setBillingMode("PAY_PER_REQUEST");
        return table;
    }

    /** The table the service reports once its billing mode is provisioned. */
    private TableDefinition provisioned() {
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE);
        table.setTableArn(TABLE_ARN);
        table.setBillingMode("PROVISIONED");
        return table;
    }

    /** Make provisioning take the update path: the table already exists with the given mode. */
    private void existingTable(String billingMode) {
        when(dynamoDbService.createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        TableDefinition table = "PAY_PER_REQUEST".equals(billingMode) ? payPerRequest() : provisioned();
        when(dynamoDbService.describeTable(TABLE, "us-east-1")).thenReturn(table);
        when(dynamoDbService.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of());
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    /** The update path: the same engine plus the physical id the previous provision assigned. */
    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Table");
        r.setResourceType("AWS::DynamoDB::Table");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource provisionTable(String properties, ProvisionContext ctx) throws Exception {
        StackResource r = resource();
        provisioner.provision(r, mapper.readTree(properties), ctx);
        return r;
    }

    // ── the reported bug ──────────────────────────────────────────────────────

    @Test
    void payPerRequestTemplateAppliesBillingMode() throws Exception {
        when(dynamoDbService.applyBillingMode(eq(TABLE), eq("PAY_PER_REQUEST"), eq("us-east-1")))
                .thenReturn(payPerRequest());

        StackResource resource = provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, ctx());

        // Previously never called: the table came out provisioned 5/5.
        verify(dynamoDbService).applyBillingMode(TABLE, "PAY_PER_REQUEST", "us-east-1");
        verify(dynamoDbService).createTable(eq(TABLE), anyList(), anyList(),
                isNull(), isNull(), anyList(), anyList(), eq("us-east-1"));
        assertEquals(TABLE, resource.getPhysicalId());
        assertEquals(TABLE_ARN, resource.getAttributes().get("Arn"));
    }

    @Test
    void provisionedTemplateWithThroughputPassesCapacityToCreate() throws Exception {
        when(dynamoDbService.applyBillingMode(eq(TABLE), eq("PROVISIONED"), eq("us-east-1")))
                .thenReturn(provisioned());

        provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"PROVISIONED",
                 "ProvisionedThroughput":{"ReadCapacityUnits":10,"WriteCapacityUnits":20},
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, ctx());

        verify(dynamoDbService).createTable(eq(TABLE), anyList(), anyList(),
                eq(10L), eq(20L), anyList(), anyList(), eq("us-east-1"));
        verify(dynamoDbService).applyBillingMode(TABLE, "PROVISIONED", "us-east-1");
    }

    // ── validation runs before the table is created ──────────────────────────

    @Test
    void payPerRequestWithThroughputIsRejectedBeforeCreate() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"PAY_PER_REQUEST",
                 "ProvisionedThroughput":{"ReadCapacityUnits":10,"WriteCapacityUnits":20},
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, ctx()));

        assertEquals("ValidationException", failure.getErrorCode());
        verify(dynamoDbService, never()).createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString());
        verify(dynamoDbService, never()).applyBillingMode(anyString(), anyString(), anyString());
    }

    @Test
    void unknownBillingModeIsRejectedBeforeCreate() throws Exception {
        assertThrows(AwsException.class, () -> provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"FOO",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, ctx()));

        verify(dynamoDbService, never()).createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString());
    }

    // ── unchanged behaviour without the property ─────────────────────────────

    @Test
    void undeclaredBillingModeLeavesTableAlone() throws Exception {
        provisionTable("""
                {"TableName":"billing-repro",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, ctx());

        // Like TTL and the stream: a stack that never declares the property leaves the
        // table's mode alone, as AWS does.
        verify(dynamoDbService, never()).applyBillingMode(anyString(), anyString(), anyString());
    }

    // ── updates reconcile the mode only when it differs ──────────────────────

    @Test
    void updateKeepsUnchangedBillingMode() throws Exception {
        existingTable("PROVISIONED");

        provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"PROVISIONED",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, updateCtx(TABLE));

        verify(dynamoDbService, never()).applyBillingMode(anyString(), anyString(), anyString());
    }

    @Test
    void updateSwitchingBillingModeAppliesIt() throws Exception {
        existingTable("PROVISIONED");
        when(dynamoDbService.applyBillingMode(eq(TABLE), eq("PAY_PER_REQUEST"), eq("us-east-1")))
                .thenReturn(payPerRequest());

        provisionTable("""
                {"TableName":"billing-repro",
                 "BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}]}
                """, updateCtx(TABLE));

        verify(dynamoDbService).applyBillingMode(TABLE, "PAY_PER_REQUEST", "us-east-1");
    }
}
