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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire-protocol reproduction for floci-io/floci#7970: {@code ExclusiveStartKey} is a position
 * in key order, not a pointer to an existing item. A Query must resume strictly after that
 * position even when the item at the position is gone (deleted, never existed, or re-keyed
 * since the previous page). The old code only matched the start key against an existing
 * item by exact equality, so a missing start key silently restarted the query from the
 * first item (base table) or resumed after the item's new position instead of the named
 * one (index query).
 */
class DynamoDbQueryExclusiveStartKeyPositionTest {

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
    void queryResumesStrictlyAfterDeletedStartKey() {
        service.deleteItem(TABLE, key("p", "s2"), REGION);

        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "s2"));

        assertEquals(List.of("s3", "s4", "s5"), sortKeys(result));
    }

    @Test
    void queryResumesStrictlyAfterNeverExistedStartKey() {
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
    void pagedWalkSurvivesDeletionOfLastEvaluatedItem() {
        DynamoDbService.QueryResult firstPage = query("PK = :p", "p", true, 2, null, null);
        assertEquals(List.of("s1", "s2"), sortKeys(firstPage));
        assertNotNull(firstPage.lastEvaluatedKey());

        // The item the cursor points at is deleted before the next page is read.
        service.deleteItem(TABLE, key("p", "s2"), REGION);

        DynamoDbService.QueryResult secondPage =
                query("PK = :p", "p", true, null, null, firstPage.lastEvaluatedKey());

        assertEquals(List.of("s3", "s4", "s5"), sortKeys(secondPage));
    }

    @Test
    void reverseQueryResumesStrictlyAfterDeletedStartKey() {
        service.deleteItem(TABLE, key("p", "s2"), REGION);

        DynamoDbService.QueryResult result = query("PK = :p", "p", false, null, null, startKey("p", "s2"));

        assertEquals(List.of("s1"), sortKeys(result));
    }

    @Test
    void queryResumesAfterExistingStartKey() {
        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "s2"));

        assertEquals(List.of("s3", "s4", "s5"), sortKeys(result));
    }

    @Test
    void queryWithStartKeyPastLastItemReturnsEmpty() {
        DynamoDbService.QueryResult result = query("PK = :p", "p", true, null, null, startKey("p", "zz"));

        assertEquals(List.of(), result.items());
        assertNull(result.lastEvaluatedKey());
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
        List<String> keys = new ArrayList<>();
        for (JsonNode item : result.items()) {
            keys.add(item.path("SK").path("S").asText());
        }
        return keys;
    }
}
