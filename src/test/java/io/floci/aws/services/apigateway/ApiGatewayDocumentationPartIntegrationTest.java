package io.floci.aws.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayDocumentationPartIntegrationTest {

    private String createApi(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void createGetListUpdateAndDeleteDocumentationPart() {
        String apiId = createApi("documentation-part-api");

        String partId = given()
                .contentType(ContentType.JSON)
                .body("{\"location\":{\"type\":\"MODEL\",\"name\":\"Example\"},\"properties\":\"{\\\"title\\\":\\\"Example\\\"}\"}")
                .when()
                .post("/restapis/" + apiId + "/documentation/parts")
                .then()
                .statusCode(201)
                .body("location.type", equalTo("MODEL"))
                .body("location.name", equalTo("Example"))
                .body("properties", equalTo("{\"title\":\"Example\"}"))
                .extract()
                .path("id");

        given()
                .when()
                .get("/restapis/" + apiId + "/documentation/parts/" + partId)
                .then()
                .statusCode(200)
                .body("id", equalTo(partId))
                .body("location.type", equalTo("MODEL"))
                .body("properties", equalTo("{\"title\":\"Example\"}"));

        given()
                .when()
                .get("/restapis/" + apiId + "/documentation/parts")
                .then()
                .statusCode(200)
                .body("item", hasSize(1))
                .body("item[0].id", equalTo(partId));

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/properties\",\"value\":\"{\\\"title\\\":\\\"Renamed\\\"}\"}]}")
                .when()
                .patch("/restapis/" + apiId + "/documentation/parts/" + partId)
                .then()
                .statusCode(200)
                .body("properties", equalTo("{\"title\":\"Renamed\"}"));

        given()
                .when()
                .delete("/restapis/" + apiId + "/documentation/parts/" + partId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get("/restapis/" + apiId + "/documentation/parts/" + partId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createDocumentationPartWithoutLocationIsRejected() {
        String apiId = createApi("documentation-part-validation-api");

        given()
                .contentType(ContentType.JSON)
                .body("{\"properties\":\"{\\\"title\\\":\\\"Example\\\"}\"}")
                .when()
                .post("/restapis/" + apiId + "/documentation/parts")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }
}
