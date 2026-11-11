package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbAttributeValueValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EMPTY_VALUE =
            "Supplied AttributeValue is empty, must contain exactly one of the supported datatypes";
    private static final String MORE_THAN_ONE_TYPE =
            "Supplied AttributeValue has more than one datatypes set, "
                    + "must contain exactly one of the supported datatypes";

    @Test
    void objectWhereStringMemberBelongsIsRefused() {
        // The #8035 repro: the M map holds {"0": "v"} where S must be a string.
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"M\":{\"S\":{\"0\":\"v\"}}}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(EMPTY_VALUE, error.getMessage());
    }

    @Test
    void topLevelValueWithNoTypeMemberIsRefused() {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"0\":\"v\"}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(EMPTY_VALUE, error.getMessage());
    }

    @Test
    void listElementWithNoTypeMemberIsRefused() {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"L\":[{\"0\":\"v\"}]}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(EMPTY_VALUE, error.getMessage());
    }

    @Test
    void valueWithTwoTypeMembersIsRefused() {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"S\":\"a\",\"N\":\"1\"}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(MORE_THAN_ONE_TYPE, error.getMessage());
    }

    @Test
    void validNestedValuesAreAccepted() {
        assertDoesNotThrow(() -> DynamoDbAttributeValueValidator.requireStorable(item(
                "{\"m\":{\"M\":{\"x\":{\"S\":\"v\"}}},\"n\":{\"L\":[{\"S\":\"a\"}]},\"s\":{\"S\":\"x\"},"
                        + "\"num\":{\"N\":\"1\"},\"flag\":{\"BOOL\":true},\"nil\":{\"NULL\":true},"
                        + "\"ss\":{\"SS\":[\"a\"]}}"), ""));
    }

    @Test
    void falseNullIsStillRefused() {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"NULL\":false}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("One or more parameter values were invalid: Null attribute value types must have the value of true",
                error.getMessage());
    }

    @Test
    void nonNumericNumberIsStillRefused() {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbAttributeValueValidator.requireStorable(
                item("{\"m\":{\"N\":\"abc\"}}"), ""));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("The parameter cannot be converted to a numeric value: abc", error.getMessage());
    }

    private static JsonNode item(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
