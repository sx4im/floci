package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire-protocol reproduction for floci-io/floci#7970: {@code ExclusiveStartKey} is a position
 * in key order, not a pointer to an existing item. A Query must resume strictly after that
 * position even when no item with that key exists any more (deleted, never existed, or
 * re-keyed since the previous page). Before the fix, the offset lookup only matched an
 * existing item by exact key equality, so a missing start key silently restarted the
 * query from the first item (base table) or returned nothing (GSI at a moved position).
 */
class DynamoDbQueryExclusiveStartKeyTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "esk-repro";

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        service.createTable(TABLE,
                List.of(new KeySchemaElement("PK", "HASH"), new KeySchemaElement("SK", "RANGE")),
                List.of(new AttributeDefinition("PK", "S"), new AttributeDefinition("SK", "S"),
                        new AttributeDefinition("G", "S"), new AttributeDefinition("GS", "S")),
                5L, 5L,
                List.of(new GlobalSecondaryIndex("gsi1",
                        List.of(new KeySchemaElement("G", "HASH"), new KeySchemaElement("GS", "RANGE")),
                        null, "ALL", List.of())), REGION);
        for (int i = 1; i <= 5; i++) {
            service.putItem(TABLE, item("s" + i, "t" + i), REGION);
        }
    }

    @Test
    void queryResumesAfterDeletedStartKey() {
        service.deleteItem(TABLE, key("p", "s2"), REGION);

        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "s2"));

        assertEquals(List.of("s3", "s4", "s5"), sortKeys(result));
    }

    @Test
    void queryResumesAfterNeverExistedStartKey() {
        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "s3x"));

        assertEquals(List.of("s4", "s5"), sortKeys(result));
    }

    @Test
    void indexQueryResumesAfterRekeyedStartKey() {
        service.putItem(TABLE, item("s3", "t9"), REGION);

        DynamoDbService.QueryResult result = indexQuery("G = :g", "live", true, null, indexStartKey());

        assertEquals(List.of("s4", "s5", "s3"), sortKeys(result));
    }

    @Test
    void queryResumesAfterExistingStartKey() {
        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "s2"));

        assertEquals(List.of("s3", "s4", "s5"), sortKeys(result));
    }

    @Test
    void reverseQueryResumesAfterDeletedStartKey() {
        service.deleteItem(TABLE, key("p", "s2"), REGION);

        DynamoDbService.QueryResult result = query("PK = :p", "p", false, null, null, startKey("p", "s2"));

        assertEquals(List.of("s1"), sortKeys(result));
    }

    @Test
    void queryWithStartKeyPastLastItemReturnsEmpty() {
        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "zz"));

        assertEquals(List.of(), result.items());
        assertNull(result.lastEvaluatedKey());
    }

    @Test
    void limitPaginationRoundTripStillWorksInBothDirections() {
        for (boolean forward : List.of(true, false)) {
            DynamoDbService.QueryResult first = query("PK = :p", "p", forward, 2, null, null);
            DynamoDbService.QueryResult second =
                    query("PK = :p", "p", forward, 2, null, first.lastEvaluatedKey());
            DynamoDbService.QueryResult third =
                    query("PK = :p", "p", forward, 2, null, second.lastEvaluatedKey());
            if (forward) {
                assertEquals(List.of("s1", "s2"), sortKeys(first));
                assertEquals(List.of("s3", "s4"), sortKeys(second));
                assertEquals(List.of("s5"), sortKeys(third));
            } else {
                assertEquals(List.of("s5", "s4"), sortKeys(first));
                assertEquals(List.of("s3", "s2"), sortKeys(second));
                assertEquals(List.of("s1"), sortKeys(third));
            }
            assertNull(third.lastEvaluatedKey());
        }
    }

    private ObjectNode item(String sk, String gs) {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("PK").put("S", "p");
        item.putObject("SK").put("S", sk);
        item.putObject("G").put("S", "live");
        item.putObject("GS").put("S", gs);
        return item;
    }

    private ObjectNode key(String pk, String sk) {
        ObjectNode key = mapper.createObjectNode();
        key.putObject("PK").put("S", pk);
        key.putObject("SK").put("S", sk);
        return key;
    }

    private ObjectNode startKey(String pk, String sk) {
        return key(pk, sk);
    }

    private ObjectNode indexStartKey() {
        ObjectNode start = mapper.createObjectNode();
        start.putObject("PK").put("S", "p");
        start.putObject("SK").put("S", "s3");
        start.putObject("G").put("S", "live");
        start.putObject("GS").put("S", "t3");
        return start;
    }

    private DynamoDbService.QueryResult query(String expression, String partitionValue, Boolean forward,
                                              Integer limit, String indexName, JsonNode start) {
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":p").put("S", partitionValue);
        return service.query(TABLE, null, values, expression, null, limit, forward,
                indexName, start, null, REGION);
    }

    private DynamoDbService.QueryResult indexQuery(String expression, String partitionValue, Boolean forward,
                                                   Integer limit, JsonNode start) {
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":g").put("S", partitionValue);
        return service.query(TABLE, null, values, expression, null, limit, forward,
                "gsi1", start, null, REGION);
    }

    private List<String> sortKeys(DynamoDbService.QueryResult result) {
        return result.items().stream().map(i -> i.path("SK").path("S").asText()).toList();
    }
}
