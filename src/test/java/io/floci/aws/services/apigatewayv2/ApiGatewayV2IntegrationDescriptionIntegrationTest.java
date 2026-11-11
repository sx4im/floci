package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Regression coverage for the HTTP API (v2) REST management API returning
 * {@code description} on Integration resources. The value was accepted on
 * Create/Update but never emitted on Get/Create/Update responses, so the AWS
 * CLI saw a null description on every read.
 */
@QuarkusTest
class ApiGatewayV2IntegrationDescriptionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void descriptionReturnedFromCreateUpdateAndGetOverRest() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"desc-rest-test",
                          "protocolType":"HTTP"
                        }
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationType": "HTTP_PROXY",
                          "integrationMethod": "GET",
                          "integrationUri": "https://example.com",
                          "payloadFormatVersion": "1.0",
                          "description": "probe"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .body("description", equalTo("probe"))
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description": "probe2"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("description", equalTo("probe2"));

        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("description", equalTo("probe2"));

        given().when().delete("/v2/apis/" + apiId).then().statusCode(204);
    }
}
