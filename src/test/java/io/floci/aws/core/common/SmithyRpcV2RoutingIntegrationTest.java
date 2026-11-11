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

    @Test
    void translateRoutesViaTargetPrefixServiceName() throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("""
                {"Text":"Ciao","SourceLanguageCode":"it","TargetLanguageCode":"de"}
                """)))
        .when()
            .post("/service/AWSShineFrontendService_20170701/operation/TranslateText")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        assertThat(CBOR_MAPPER.readTree(response)
                .path("TranslatedText").asText(), equalTo("Ciao"));
    }

    @Test
    void translateDocumentRoundTripsContentAsCborByteString() throws Exception {
        // Non-UTF-8 bytes included on purpose: Document.Content is a blob, not text.
        byte[] document = {'H', 'e', 'l', 'l', 'o', ' ', (byte) 0xC3, (byte) 0xA9, 0x00, (byte) 0xFF, '\n'};
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document")
                .put("Content", document)
                .put("ContentType", "text/plain");
        request.put("SourceLanguageCode", "en");
        request.put("TargetLanguageCode", "es");

        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(request))
        .when()
            .post("/service/AWSShineFrontendService_20170701/operation/TranslateDocument")
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();

        JsonNode decoded = CBOR_MAPPER.readTree(response);
        JsonNode content = decoded.path("TranslatedDocument").path("Content");
        assertThat(content.isBinary(), equalTo(true));
        assertThat(content.binaryValue(), equalTo(document));
        assertThat(decoded.path("SourceLanguageCode").asText(), equalTo("en"));
        assertThat(decoded.path("TargetLanguageCode").asText(), equalTo("es"));
    }

    /**
     * One operation per JSON service that used to answer 404 over
     * smithy-rpc-v2-cbor (floci-io/floci#7983): each now routes through the
     * JSON 1.1 handlers instead of needing its own CBOR case.
     */
    private JsonNode postCborAndDecode(String serviceName, String operation, JsonNode request) throws Exception {
        byte[] response = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(request))
        .when()
            .post("/service/" + serviceName + "/operation/" + operation)
        .then()
            .statusCode(200)
            .header("smithy-protocol", equalTo("rpc-v2-cbor"))
            .contentType("application/cbor")
            .extract().asByteArray();
        return CBOR_MAPPER.readTree(response);
    }

    @Test
    void cloudHsmV2DescribeClustersRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("BaldrApiService", "DescribeClusters",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("Clusters"), notNullValue());
    }

    @Test
    void codeBuildListProjectsRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("CodeBuild_20161006", "ListProjects",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("projects"), notNullValue());
    }

    @Test
    void codeDeployListApplicationsRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("CodeDeploy_20141006", "ListApplications",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("applications"), notNullValue());
    }

    @Test
    void codePipelineListPipelinesRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("CodePipeline_20150709", "ListPipelines",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("pipelines"), notNullValue());
    }

    @Test
    void comprehendDetectSentimentRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("Comprehend_20171127", "DetectSentiment",
                JSON_MAPPER.readTree("""
                    {"Text": "Hello world", "LanguageCode": "en"}
                    """));
        assertThat(decoded.path("Sentiment"), notNullValue());
    }

    @Test
    void dataSyncListTasksRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("FmrsService", "ListTasks",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("Tasks"), notNullValue());
    }

    @Test
    void memoryDbDescribeClustersRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("AmazonMemoryDB", "DescribeClusters",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("Clusters"), notNullValue());
    }

    @Test
    void networkFirewallListFirewallsRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("NetworkFirewall_20201112", "ListFirewalls",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("Firewalls"), notNullValue());
    }

    @Test
    void taggingGetResourcesRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("ResourceGroupsTaggingAPI_20170126", "GetResources",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("ResourceTagMappingList"), notNullValue());
    }

    @Test
    void serviceCatalogListPortfoliosRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("AWS242ServiceCatalogService", "ListPortfolios",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("PortfolioDetails"), notNullValue());
    }

    @Test
    void cloudMapListNamespacesRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("Route53AutoNaming_v20170314", "ListNamespaces",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("Namespaces"), notNullValue());
    }

    @Test
    void textractDetectDocumentTextRoutesViaTargetPrefixServiceName() throws Exception {
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document").put("Bytes", new byte[] {'H', 'i', (byte) 0xFF});
        JsonNode decoded = postCborAndDecode("Textract", "DetectDocumentText", request);
        assertThat(decoded.path("Blocks"), notNullValue());
    }

    @Test
    void transcribeListTranscriptionJobsRoutesViaTargetPrefixServiceName() throws Exception {
        JsonNode decoded = postCborAndDecode("Transcribe", "ListTranscriptionJobs",
                JSON_MAPPER.createObjectNode());
        assertThat(decoded.path("TranscriptionJobSummaries"), notNullValue());
    }
}
