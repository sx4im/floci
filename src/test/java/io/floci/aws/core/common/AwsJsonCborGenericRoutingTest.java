package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.floci.aws.services.dynamodb.DynamoDbJsonHandler;
import io.floci.aws.services.dynamodb.DynamoDbStreamsJsonHandler;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Handler;
import io.floci.aws.services.kinesis.KinesisJsonHandler;
import io.floci.aws.services.marketplace.MarketplaceEntitlementController;
import io.floci.aws.services.sns.SnsJsonHandler;
import io.floci.aws.services.sqs.SqsJsonHandler;
import io.floci.aws.services.stepfunctions.StepFunctionsJsonHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the generic smithy-rpc-v2-cbor routing in
 * {@link AwsJsonCborController}: every JSON-protocol service answers over CBOR
 * through the same controllers its JSON requests already go through, instead
 * of one switch case per service (floci-io/floci#7983).
 * <p>
 * The JSON controllers are mocked here; the routing decision itself (which
 * controller, and the unknown-operation fallback between them) is what is
 * under test. No Quarkus boot.
 */
@ExtendWith(MockitoExtension.class)
class AwsJsonCborGenericRoutingTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    @Mock
    private ResolvedServiceCatalog catalog;
    @Mock
    private RegionResolver regionResolver;
    @Mock
    private DynamoDbJsonHandler dynamoDbJsonHandler;
    @Mock
    private DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler;
    @Mock
    private SqsJsonHandler sqsJsonHandler;
    @Mock
    private SnsJsonHandler snsJsonHandler;
    @Mock
    private KinesisJsonHandler kinesisJsonHandler;
    @Mock
    private StepFunctionsJsonHandler sfnJsonHandler;
    @Mock
    private CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler;
    @Mock
    private MarketplaceEntitlementController marketplaceEntitlementController;
    @Mock
    private EventBridgeV2Handler eventBridgeV2Handler;
    @Mock
    private AwsJson11Controller json11Controller;
    @Mock
    private AwsJsonController jsonController;
    @Mock
    private HttpHeaders httpHeaders;
    @Mock
    private UriInfo uriInfo;

    private AwsJsonCborController controller() {
        return new AwsJsonCborController(JSON_MAPPER, catalog, regionResolver,
                dynamoDbJsonHandler, dynamoDbStreamsJsonHandler,
                sqsJsonHandler, snsJsonHandler, kinesisJsonHandler, sfnJsonHandler,
                cloudWatchMetricsJsonHandler, marketplaceEntitlementController,
                eventBridgeV2Handler, json11Controller, jsonController);
    }

    private static ServiceDescriptor descriptor(String externalKey, String targetPrefix) {
        return new ServiceDescriptor(externalKey, externalKey, true, true, null, null, 0L, null,
                ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON), Set.of(targetPrefix),
                Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static Response unknownOperation(String target) {
        ObjectNode error = JSON_MAPPER.createObjectNode();
        error.put("__type", "UnknownOperationException");
        error.put("message", "Unknown operation: " + target);
        return Response.status(404).entity(error).build();
    }

    private static Response okJson(String json) throws Exception {
        return Response.status(200).entity(JSON_MAPPER.readTree(json)).build();
    }

    private void stubHeaders() {
        lenient().when(httpHeaders.getHeaderString("Content-Type")).thenReturn("application/cbor");
        lenient().when(httpHeaders.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER))
                .thenReturn(null);
        lenient().when(httpHeaders.getRequestHeader("Content-encoding")).thenReturn(null);
        lenient().when(regionResolver.resolveRegion(httpHeaders)).thenReturn("us-east-1");
    }

    private byte[] emptyCborBody() throws Exception {
        return CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.createObjectNode());
    }

    @Test
    void json11ServiceRoutesThroughJson11Controller() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("CodeBuild_20161006"))
                .thenReturn(Optional.of(descriptor("codebuild", "CodeBuild_20161006.")));
        when(json11Controller.handle(eq("CodeBuild_20161006.ListProjects"), any(), any(), anyString()))
                .thenReturn(okJson("{\"projects\": []}"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "CodeBuild_20161006", "ListProjects", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(200, response.getStatus());
        JsonNode decoded = CBOR_MAPPER.readTree((byte[]) response.getEntity());
        assertTrue(decoded.path("projects").isArray(), "ListProjects must come back over CBOR");
        assertEquals(0, decoded.path("projects").size());
        verify(jsonController, never()).handleJsonRequest(anyString(), any(), anyString());
    }

    @Test
    void json10OnlyServiceFallsBackToJson10Controller() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("SimpleWorkflowService_20120125"))
                .thenReturn(Optional.of(descriptor("swf", "SimpleWorkflowService_20120125.")));
        when(json11Controller.handle(eq("SimpleWorkflowService_20120125.ListDomains"), any(), any(), anyString()))
                .thenReturn(unknownOperation("SimpleWorkflowService_20120125.ListDomains"));
        when(jsonController.handleJsonRequest(eq("SimpleWorkflowService_20120125.ListDomains"), any(), anyString()))
                .thenReturn(okJson("{\"domainInfos\": []}"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "SimpleWorkflowService_20120125", "ListDomains", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(200, response.getStatus());
        JsonNode decoded = CBOR_MAPPER.readTree((byte[]) response.getEntity());
        assertTrue(decoded.path("domainInfos").isArray(), "SWF must come back over CBOR via JSON 1.0");
    }

    @Test
    void nonJsonServiceStillReturns404() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("AmazonIdentityManagement"))
                .thenReturn(Optional.of(descriptor("iam", "AmazonIdentityManagement.")));
        // Lenient: the pre-fix controller never reaches the JSON controllers,
        // so these stubbings are only exercised once the fallback exists.
        lenient().when(json11Controller.handle(anyString(), any(), any(), anyString()))
                .thenReturn(unknownOperation("AmazonIdentityManagement.ListUsers"));
        lenient().when(jsonController.handleJsonRequest(anyString(), any(), anyString()))
                .thenReturn(unknownOperation("AmazonIdentityManagement.ListUsers"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "AmazonIdentityManagement", "ListUsers", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(404, response.getStatus());
        assertNull(response.getEntity(), "a non-JSON service keeps the bare 404");
    }

    @Test
    void unknownServiceNameStillReturns404() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("NoSuchService")).thenReturn(Optional.empty());

        Response response = controller().handleSmithyRpcV2Cbor(
                "NoSuchService", "DoThing", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(404, response.getStatus());
        verify(json11Controller, never()).handle(anyString(), any(), any(), anyString());
        verify(jsonController, never()).handleJsonRequest(anyString(), any(), anyString());
    }

    @Test
    void alreadyRoutedServiceStillBypassesJsonControllers() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("DynamoDB_20120810"))
                .thenReturn(Optional.of(descriptor("dynamodb", "DynamoDB_20120810.")));
        when(dynamoDbJsonHandler.handle(eq("ListTables"), any(), eq("us-east-1")))
                .thenReturn(okJson("{\"TableNames\": []}"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "DynamoDB_20120810", "ListTables", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(200, response.getStatus());
        JsonNode decoded = CBOR_MAPPER.readTree((byte[]) response.getEntity());
        assertTrue(decoded.path("TableNames").isArray());
        verify(json11Controller, never()).handle(anyString(), any(), any(), anyString());
        verify(jsonController, never()).handleJsonRequest(anyString(), any(), anyString());
    }

    @Test
    void nonUnknownOperationErrorIsReturnedWithoutFallback() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("CodeBuild_20161006"))
                .thenReturn(Optional.of(descriptor("codebuild", "CodeBuild_20161006.")));
        ObjectNode error = JSON_MAPPER.createObjectNode();
        error.put("__type", "ValidationException");
        error.put("message", "bad input");
        when(json11Controller.handle(eq("CodeBuild_20161006.ListProjects"), any(), any(), anyString()))
                .thenReturn(Response.status(400).entity(error).build());

        Response response = controller().handleSmithyRpcV2Cbor(
                "CodeBuild_20161006", "ListProjects", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(400, response.getStatus());
        verify(jsonController, never()).handleJsonRequest(anyString(), any(), anyString());
    }

    @Test
    void cborByteStringBecomesBase64TextForHandlers() throws Exception {
        stubHeaders();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("Bytes", "hello".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Document", document);
        byte[] cbor = CBOR_MAPPER.writeValueAsBytes(request);

        JsonNode decoded = controller().bodyToJson(httpHeaders, cbor);

        JsonNode bytes = decoded.path("Document").path("Bytes");
        assertTrue(bytes.isTextual(), "a CBOR byte string must reach handlers as JSON-form base64 text");
        assertEquals("aGVsbG8=", bytes.asText());
    }

    @Test
    void transcribeTimestampsAreTagged() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("Transcribe"))
                .thenReturn(Optional.of(descriptor("transcribe", "Transcribe.")));
        when(json11Controller.handle(eq("Transcribe.ListTranscriptionJobs"), any(), any(), anyString()))
                .thenReturn(okJson("{\"TranscriptionJobSummaries\": [{\"TranscriptionJobName\": \"job\","
                        + " \"CreationTime\": 1700000000, \"StartTime\": 1700000001,"
                        + " \"CompletionTime\": 1700000060}]}"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "Transcribe", "ListTranscriptionJobs", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(200, response.getStatus());
        byte[] cbor = (byte[]) response.getEntity();
        assertEquals(3, countTag1(cbor), "CreationTime, StartTime and CompletionTime must each carry tag(1)");
        JsonNode decoded = CBOR_MAPPER.readTree(cbor);
        assertEquals(1700000000L, decoded.path("TranscriptionJobSummaries").path(0).path("CreationTime").asLong());
    }

    @Test
    void codePipelineFractionalTimestampsAreTagged() throws Exception {
        stubHeaders();
        when(catalog.byCborSdkServiceId("CodePipeline_20150709"))
                .thenReturn(Optional.of(descriptor("codepipeline", "CodePipeline_20150709.")));
        when(json11Controller.handle(eq("CodePipeline_20150709.ListPipelines"), any(), any(), anyString()))
                .thenReturn(okJson("{\"pipelines\": [{\"name\": \"p\","
                        + " \"created\": 1700000000.5, \"updated\": 1700000060.25}]}"));

        Response response = controller().handleSmithyRpcV2Cbor(
                "CodePipeline_20150709", "ListPipelines", httpHeaders, uriInfo, emptyCborBody());

        assertEquals(200, response.getStatus());
        byte[] cbor = (byte[]) response.getEntity();
        assertEquals(2, countTag1(cbor), "created and updated must each carry tag(1)");
        JsonNode decoded = CBOR_MAPPER.readTree(cbor);
        assertEquals(1700000000.5, decoded.path("pipelines").path(0).path("created").asDouble());
    }

    private static int countTag1(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) {
            if (b == (byte) 0xC1) {
                count++;
            }
        }
        return count;
    }

    @Test
    void plainJsonFieldsPassThroughBlobNormalizationUnchanged() throws Exception {
        stubHeaders();
        byte[] cbor = CBOR_MAPPER.writeValueAsBytes(Map.of("Text", "hello", "Count", 3));

        JsonNode decoded = controller().bodyToJson(httpHeaders, cbor);

        assertEquals("hello", decoded.path("Text").asText());
        assertEquals(3L, decoded.path("Count").asLong());
    }
}
