package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreEndpointTaggingIntegrationTest {

    private static final String CREATE_RUNTIME = """
            {
              "agentRuntimeName": "epTagAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent",
              "tags": {"team": "runtime"}
            }""";

    private static String runtimeId;
    private static String runtimeArn;
    private static String endpointArn;

    @Test
    @Order(1)
    void createEndpointKeepsCreateTags() {
        ExtractableResponse<Response> runtime = given().contentType("application/json").body(CREATE_RUNTIME)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .extract();
        runtimeId = runtime.path("agentRuntimeId");
        runtimeArn = runtime.path("agentRuntimeArn");

        ExtractableResponse<Response> endpoint = given().contentType("application/json")
                .body("{\"name\":\"prod\",\"agentRuntimeVersion\":\"1\",\"tags\":{\"team\":\"core\"}}")
                .when().put("/runtimes/" + runtimeId + "/runtime-endpoints/")
                .then().statusCode(202)
                .body("endpointName", equalTo("prod"))
                .extract();
        endpointArn = endpoint.path("agentRuntimeEndpointArn");

        // Create tags land on the endpoint, not on the runtime.
        given().when().get("/tags/" + endpointArn)
                .then().statusCode(200)
                .body("tags.team", equalTo("core"));
        given().when().get("/tags/" + runtimeArn)
                .then().statusCode(200)
                .body("tags.team", equalTo("runtime"));
    }

    @Test
    @Order(2)
    void endpointTagUntagRoundTripKeepsRuntimeTagsApart() {
        given().contentType("application/json").body("{\"tags\":{\"env\":\"dev\"}}")
                .when().post("/tags/" + endpointArn)
                .then().statusCode(204);

        given().when().get("/tags/" + endpointArn)
                .then().statusCode(200)
                .body("tags.team", equalTo("core"))
                .body("tags.env", equalTo("dev"));
        given().when().get("/tags/" + runtimeArn)
                .then().statusCode(200)
                .body("tags.env", nullValue());

        given().when().delete("/tags/" + endpointArn + "?tagKeys=env")
                .then().statusCode(204);

        given().when().get("/tags/" + endpointArn)
                .then().statusCode(200)
                .body("tags.team", equalTo("core"))
                .body("tags.env", nullValue());
    }

    @Test
    @Order(3)
    void nestedEndpointArnResolvesAndMissingEndpointReturns404() {
        String nested = "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/"
                + runtimeId + "/runtime-endpoint/prod";
        given().when().get("/tags/" + nested)
                .then().statusCode(200)
                .body("tags.team", equalTo("core"));

        String missingNested = "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/"
                + runtimeId + "/runtime-endpoint/nope";
        given().when().get("/tags/" + missingNested)
                .then().statusCode(404);

        String missingUuid = "arn:aws:bedrock-agentcore:us-east-1:000000000000:agentEndpoint/00000000-0000-0000-0000-000000000000";
        given().when().get("/tags/" + missingUuid)
                .then().statusCode(404);
    }

    @Test
    @Order(4)
    void foreignAccountOrRegionEndpointArnReturns404() {
        String foreignAccount = endpointArn.replace(":000000000000:", ":111111111111:");
        String foreignRegion = endpointArn.replace(":us-east-1:", ":eu-west-1:");

        given().when().get("/tags/" + foreignAccount).then().statusCode(404);
        given().when().get("/tags/" + foreignRegion).then().statusCode(404);

        given().contentType("application/json").body("{\"tags\":{\"env\":\"stolen\"}}")
                .when().post("/tags/" + foreignAccount)
                .then().statusCode(404);
    }
}
