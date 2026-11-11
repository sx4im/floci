package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Exercises the smithy-rpc-v2-cbor wire format exactly as the AWS SDKs send it:
 * POST /service/{serviceShapeName}/operation/{operation} where the service name
 * segment is the X-Amz-Target prefix without its trailing dot (e.g.
 * DynamoDB_20120810, Kinesis_20131202, GraniteServiceVersion20100801), with the
 * smithy-protocol request header and a generic application/cbor content type.
 * Also covers the sibling legacy format (x-amz-cbor-1.1 + X-Amz-Target at the
 * root path) and content-type normalization during protocol transitions.
 * Captured from aws-sdk-java 2.46.7 — see local/plan/smithy/captured/SUMMARY.md.
 */
@QuarkusTest
class SmithyRpcV2RoutingIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void dynamoDbRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode createRequest = JSON_MAPPER.readTree("""
            {
                "TableName": "RpcV2WireTable",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
            }
            """);

        byte[] created = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(createRequest))
        .when()
            .post("/service/DynamoDB_20120810/operation/CreateTable")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(created)
                .path("TableDescription").path("TableName").asText(), equalTo("RpcV2WireTable"));

        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("""
                {"TableName": "RpcV2WireTable"}
                """)))
        .when()
            .post("/service/DynamoDB_20120810/operation/DeleteTable")
        .then()
            .statusCode(200);
    }

    @Test
    void kinesisRoutesViaTargetPrefixServiceName() throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("StreamNames"), notNullValue());
    }

    @Test
    void cloudWatchRoutesViaGraniteServiceName() throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/GraniteServiceVersion20100801/operation/ListMetrics")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("Metrics"), notNullValue());
    }

    @Test
    void unknownServiceNameReturns404() throws Exception {
        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/NoSuchService/operation/DoThing")
        .then()
            .statusCode(404);
    }

    @Test
    void legacyCborTargetAtRootEchoesAmzCborContentType() throws Exception {
        // The live Kinesis format from aws-sdk-java: x-amz-cbor-1.1 body with
        // X-Amz-Target at the root path, no smithy-protocol request header.
        byte[] response = given()
            .contentType("application/x-amz-cbor-1.1")
            .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/x-amz-cbor-1.1")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response).path("StreamNames"), notNullValue());
    }

    @Test
    void smithyProtocolHeaderNormalizesDriftedContentType() throws Exception {
        // A rpcv2Cbor request whose Content-Type drifted away from application/cbor
        // must still route: the smithy-protocol header is the definitive claim
        // signal and AwsCborContentTypeFilter normalizes the media type for it.
        given()
            .contentType("application/octet-stream")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"));
    }

    @Test
    void straySmithyHeaderOutsideRpcV2PathDoesNotRewriteContentType() {
        // A Smithy-Protocol header on a non-rpcv2 path must not trigger content
        // type normalization: later filters (e.g. S3VirtualHostFilter) rely on
        // seeing the original management content type, and the request must
        // still reach its normal JSON controller.
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Smithy-Protocol", "rpc-v2-cbor")
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void amzCbor11OnRpcV2PathEchoesOriginalContentType() throws Exception {
        given()
            .contentType("application/x-amz-cbor-1.1")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode()))
        .when()
            .post("/service/Kinesis_20131202/operation/ListStreams")
        .then()
            .statusCode(200)
            .contentType("application/x-amz-cbor-1.1");
    }

    /**
     * Posts one CBOR-encoded operation to the smithy-rpc-v2-cbor path and
     * returns the decoded response node. Asserts the 200 + rpc-v2-cbor echo,
     * so each per-service test below only names its service, operation and
     * body (floci-io/floci#7983: every JSON service must answer over CBOR,
     * routed through the shared JSON 1.1 dispatch).
     */
    private JsonNode postRpcV2(String serviceId, String operation, JsonNode body) throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(body))
        .when()
            .post("/service/" + serviceId + "/operation/" + operation)
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        JsonNode decoded = CBOR_MAPPER.readTree(response);
        assertThat(decoded.isObject(), equalTo(true));
        return decoded;
    }

    @Test
    void codeBuildListsProjectsOverCbor() throws Exception {
        assertThat(postRpcV2("CodeBuild_20161006", "ListProjects",
                JSON_MAPPER.createObjectNode()).path("projects"), notNullValue());
    }

    @Test
    void codeDeployListsApplicationsOverCbor() throws Exception {
        assertThat(postRpcV2("CodeDeploy_20141006", "ListApplications",
                JSON_MAPPER.createObjectNode()).path("applications"), notNullValue());
    }

    @Test
    void codePipelineListsPipelinesOverCbor() throws Exception {
        assertThat(postRpcV2("CodePipeline_20150709", "ListPipelines",
                JSON_MAPPER.createObjectNode()).path("pipelines"), notNullValue());
    }

    @Test
    void comprehendDetectsDominantLanguageOverCbor() throws Exception {
        assertThat(postRpcV2("Comprehend_20171127", "DetectDominantLanguage",
                JSON_MAPPER.readTree("""
                    {"Text": "Hello world"}
                    """)).path("Languages"), notNullValue());
    }

    @Test
    void dataSyncListsTasksOverCbor() throws Exception {
        assertThat(postRpcV2("FmrsService", "ListTasks",
                JSON_MAPPER.createObjectNode()).path("Tasks"), notNullValue());
    }

    @Test
    void cloudHsmDescribesClustersOverCbor() throws Exception {
        assertThat(postRpcV2("BaldrApiService", "DescribeClusters",
                JSON_MAPPER.createObjectNode()).path("Clusters"), notNullValue());
    }

    @Test
    void cloudMapListsNamespacesOverCbor() throws Exception {
        assertThat(postRpcV2("Route53AutoNaming_v20170314", "ListNamespaces",
                JSON_MAPPER.createObjectNode()).path("Namespaces"), notNullValue());
    }

    @Test
    void memoryDbDescribesClustersOverCbor() throws Exception {
        assertThat(postRpcV2("AmazonMemoryDB", "DescribeClusters",
                JSON_MAPPER.createObjectNode()).path("Clusters"), notNullValue());
    }

    @Test
    void networkFirewallListsFirewallsOverCbor() throws Exception {
        assertThat(postRpcV2("NetworkFirewall_20201112", "ListFirewalls",
                JSON_MAPPER.createObjectNode()).path("Firewalls"), notNullValue());
    }

    @Test
    void taggingGetsResourcesOverCbor() throws Exception {
        assertThat(postRpcV2("ResourceGroupsTaggingAPI_20170126", "GetResources",
                JSON_MAPPER.createObjectNode()).path("ResourceTagMappingList"), notNullValue());
    }

    @Test
    void serviceCatalogListsPortfoliosOverCbor() throws Exception {
        assertThat(postRpcV2("AWS242ServiceCatalogService", "ListPortfolios",
                JSON_MAPPER.createObjectNode()).path("PortfolioDetails"), notNullValue());
    }

    @Test
    void textractDetectsDocumentTextOverCbor() throws Exception {
        // The document arrives as a CBOR byte string; the controller must
        // hand the handler base64 text, as the JSON handlers already expect.
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document").put("Bytes", new byte[] {0x00, 0x01, (byte) 0xFF, 'a'});
        postRpcV2("Textract", "DetectDocumentText", request);
    }

    @Test
    void transcribeListsTranscriptionJobsOverCbor() throws Exception {
        assertThat(postRpcV2("Transcribe", "ListTranscriptionJobs",
                JSON_MAPPER.createObjectNode()).path("TranscriptionJobSummaries"), notNullValue());
    }

    @Test
    void translateTranslatesTextOverCbor() throws Exception {
        assertThat(postRpcV2("AWSShineFrontendService_20170701", "TranslateText",
                JSON_MAPPER.readTree("""
                    {"Text": "Hello", "SourceLanguageCode": "en", "TargetLanguageCode": "es"}
                    """)).path("TranslatedText").asText(), equalTo("Hello"));
    }

    @Test
    void translateDocumentReturnsBlobAsCborByteString() throws Exception {
        // The document goes out as a CBOR byte string and the translated
        // document comes back as one: base64 text on the JSON side only.
        byte[] document = {'H', 'e', 'l', 'l', 'o', ' ', (byte) 0xC3, (byte) 0xA9, 0x00, (byte) 0xFF};
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document")
                .put("Content", document)
                .put("ContentType", "text/plain");
        request.put("SourceLanguageCode", "en");
        request.put("TargetLanguageCode", "es");
        JsonNode content = postRpcV2("AWSShineFrontendService_20170701", "TranslateDocument", request)
                .path("TranslatedDocument").path("Content");
        assertThat(content.isBinary(), equalTo(true));
        assertArrayEquals(document, content.binaryValue());
    }
}
