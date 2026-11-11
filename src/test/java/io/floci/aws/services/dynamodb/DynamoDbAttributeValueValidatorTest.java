package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbAttributeValueValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode item(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static AwsException refused(String json) throws Exception {
        return assertThrows(AwsException.class,
                () -> DynamoDbAttributeValueValidator.requireStorable(item(json), ""));
    }

    @Test
    void nestedObjectAsSIsRefused() throws Exception {
        AwsException thrown = refused("{\"pk\":{\"S\":\"a\"},\"m\":{\"M\":{\"S\":{\"0\":\"v\"}}}}");
        assertEquals("ValidationException", thrown.getErrorCode());
        assertEquals(400, thrown.getHttpStatus());
        assertTrue(thrown.getMessage().contains("Supplied AttributeValue is empty"));
    }

    @Test
    void emptyValueIsRefused() throws Exception {
        AwsException thrown = refused("{\"m\":{}}");
        assertEquals("ValidationException", thrown.getErrorCode());
        assertEquals(400, thrown.getHttpStatus());
    }

    @Test
    void unknownMemberValueIsRefused() throws Exception {
        AwsException thrown = refused("{\"m\":{\"ZZZ\":1}}");
        assertEquals("ValidationException", thrown.getErrorCode());
        assertEquals(400, thrown.getHttpStatus());
    }

    @Test
    void nullTypedValueIsLeftToTheOperation() throws Exception {
        assertDoesNotThrow(() -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"pk\":{\"S\":null}}"), ""));
    }

    @Test
    void validValuesPass() throws Exception {
        assertDoesNotThrow(() -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"pk\":{\"S\":\"a\"},\"n\":{\"N\":\"42\"},\"b\":{\"BOOL\":true},"
                        + "\"z\":{\"NULL\":true},\"m\":{\"M\":{\"S\":{\"S\":\"x\"}}},"
                        + "\"l\":{\"L\":[{\"N\":\"1\"}]},\"ss\":{\"SS\":[\"a\",\"b\"]}}"), ""));
    }
}
