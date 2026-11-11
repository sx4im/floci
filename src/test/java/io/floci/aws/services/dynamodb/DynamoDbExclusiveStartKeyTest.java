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
 * floci-io/floci#7970: an ExclusiveStartKey is a position in key order, not a
 * pointer to an existing item. A Query must resume strictly after that position
 * even when no item with that key exists any more, for example because the item
 * was deleted or its index sort key changed between two pages.
 */
class DynamoDbExclusiveStartKeyTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        service.createTable("Orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S"),
                        new AttributeDefinition("gpk", "S")), 5L, 5L,
                List.of(new GlobalSecondaryIndex("ByGroup",
                        List.of(new KeySchemaElement("gpk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                        null, "ALL", List.of())), REGION);
        for (String sk : List.of("a1", "a2", "a3", "b1")) {
            service.putItem("Orders", item("user", sk), REGION);
        }
    }

    @Test
    void queryResumesAfterDeletedStartKey() {
        ObjectNode deleted = mapper.createObjectNode();
        deleted.putObject("pk").put("S", "user");
        deleted.putObject("sk").put("S", "a2");
        service.deleteItem("Orders", deleted, REGION);
        assertEquals(List.of("a3", "b1"), sortKeys(query("user", "pk = :p", true, null, deleted)));
    }

    @Test
    void queryResumesAfterNeverExistedStartKey() {
        ObjectNode start = mapper.createObjectNode();
        start.putObject("pk").put("S", "user");
        start.putObject("sk").put("S", "a2x");
        assertEquals(List.of("a3", "b1"), sortKeys(query("user", "pk = :p", true, null, start)));
        // Descending, a2x sits between a3 and a2, so a2 is still ahead of the resume point.
        assertEquals(List.of("a2", "a1"), sortKeys(query("user", "pk = :p", false, null, start)));
    }

    @Test
    void queryResumesAtCollectionBoundaries() {
        ObjectNode pastEnd = mapper.createObjectNode();
        pastEnd.putObject("pk").put("S", "user");
        pastEnd.putObject("sk").put("S", "z9");
        DynamoDbService.QueryResult forwardPastEnd = query("user", "pk = :p", true, null, pastEnd);
        assertEquals(List.of(), sortKeys(forwardPastEnd));
        assertNull(forwardPastEnd.lastEvaluatedKey());
        assertEquals(List.of("b1", "a3", "a2", "a1"),
                sortKeys(query("user", "pk = :p", false, null, pastEnd)));
        ObjectNode beforeStart = mapper.createObjectNode();
        beforeStart.putObject("pk").put("S", "user");
        beforeStart.putObject("sk").put("S", "0");
        assertEquals(List.of("a1", "a2", "a3", "b1"),
                sortKeys(query("user", "pk = :p", true, null, beforeStart)));
        assertEquals(List.of(), sortKeys(query("user", "pk = :p", false, null, beforeStart)));
    }

    @Test
    void indexQueryResumesAfterMovedStartKey() {
        service.createTable("EskRepro",
                List.of(new KeySchemaElement("PK", "HASH"), new KeySchemaElement("SK", "RANGE")),
                List.of(new AttributeDefinition("PK", "S"), new AttributeDefinition("SK", "S"),
                        new AttributeDefinition("G", "S"), new AttributeDefinition("GS", "S")),
                5L, 5L,
                List.of(new GlobalSecondaryIndex("gsi1",
                        List.of(new KeySchemaElement("G", "HASH"), new KeySchemaElement("GS", "RANGE")),
                        null, "ALL", List.of())), REGION);
        for (int i = 1; i <= 5; i++) {
            service.putItem("EskRepro", gsiItem("s" + i, "t" + i), REGION);
        }
        // s3 moves from t3 to t9; the start key names its previous index position.
        service.putItem("EskRepro", gsiItem("s3", "t9"), REGION);
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":g").put("S", "live");
        ObjectNode start = mapper.createObjectNode();
        start.putObject("PK").put("S", "p");
        start.putObject("SK").put("S", "s3");
        start.putObject("G").put("S", "live");
        start.putObject("GS").put("S", "t3");
        DynamoDbService.QueryResult result = service.query("EskRepro", null, values, "G = :g",
                null, null, true, "gsi1", start, null, REGION);
        assertEquals(List.of("s4", "s5", "s3"),
                result.items().stream().map(i -> i.path("SK").path("S").asText()).toList());
    }

    private ObjectNode item(String pk, String sk) {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("pk").put("S", pk);
        item.putObject("sk").put("S", sk);
        item.putObject("gpk").put("S", "shared");
        return item;
    }

    private ObjectNode gsiItem(String sk, String gs) {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("PK").put("S", "p");
        item.putObject("SK").put("S", sk);
        item.putObject("G").put("S", "live");
        item.putObject("GS").put("S", gs);
        return item;
    }

    private DynamoDbService.QueryResult query(String pk, String expression, Boolean forward,
                                              Integer limit, JsonNode start) {
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":p").put("S", pk);
        return service.query("Orders", null, values, expression, null, limit, forward,
                null, start, null, REGION);
    }

    private List<String> sortKeys(DynamoDbService.QueryResult result) {
        return result.items().stream().map(i -> i.path("sk").path("S").asText()).toList();
    }
}
