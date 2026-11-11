package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.floci.aws.services.translate.TranslateJsonHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast unit test for the generic smithy-rpc-v2-cbor routing in
 * {@link AwsJsonCborController}: services without their own CBOR case reach
 * {@link AwsJson11Controller#dispatch} instead of a bare 404 (floci-io/floci#7983).
 * The wire end-to-end coverage lives in {@link SmithyRpcV2RoutingIntegrationTest};
 * this test guards the dispatch decision without booting Quarkus.
 */
class AwsJsonCborGenericRoutingTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final URI BASE_URI = URI.create("http://localhost:4566/");
    private static final String ACCOUNT_ID = "123456789012";

    private final ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler =
            mock(CloudWatchMetricsJsonHandler.class);
    private final TranslateJsonHandler translateJsonHandler = mock(TranslateJsonHandler.class);
    private final AwsJson11Controller json11Controller = mock(AwsJson11Controller.class);

    private static ServiceDescriptor descriptor(String externalKey, String rpcV2Name) {
        return new ServiceDescriptor(externalKey, externalKey, true, true, null, null, 5000L, null,
                ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON), Set.of(rpcV2Name + "."),
                Set.of(externalKey), Set.of(), Set.of(), Set.of());
    }

    private AwsJsonCborController controller() {
        when(regionResolver.resolveRegion(any())).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        return new AwsJsonCborController(JSON_MAPPER, catalog, regionResolver,
                mock(DynamoDbJsonHandler.class), mock(DynamoDbStreamsJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(KinesisJsonHandler.class), mock(StepFunctionsJsonHandler.class),
                cloudWatchMetricsJsonHandler, mock(MarketplaceEntitlementController.class),
                mock(EventBridgeV2Handler.class), translateJsonHandler, json11Controller);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeader("Content-encoding")).thenReturn(null);
        when(headers.getHeaderString(any())).thenReturn(null);
        return headers;
    }

    private UriInfo uriInfo() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(BASE_URI);
        return uriInfo;
    }

    private byte[] cbor(JsonNode node) throws Exception {
        return CBOR_MAPPER.writeValueAsBytes(node);
    }

    private Response route(String serviceId, String operation, JsonNode request) throws Exception {
        return controller().handleSmithyRpcV2Cbor(serviceId, operation, headers(), uriInfo(), cbor(request));
    }

    @Test
    void codeBuildListProjectsRoutesThroughJson11Dispatch() throws Exception {
        when(catalog.byCborSdkServiceId("CodeBuild_20161006"))
                .thenReturn(Optional.of(descriptor("codebuild", "CodeBuild_20161006")));
        when(json11Controller.dispatch(eq("codebuild"), eq("ListProjects"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull()))
                .thenReturn(Response.ok(JSON_MAPPER.createObjectNode()).build());

        Response response = route("CodeBuild_20161006", "ListProjects", JSON_MAPPER.createObjectNode());

        assertEquals(200, response.getStatus(),
                "CodeBuild ListProjects over smithy-rpc-v2-cbor must route, not 404 (floci#7983)");
        verify(json11Controller).dispatch(eq("codebuild"), eq("ListProjects"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull());
    }

    @Test
    void transcribeListJobsRoutesThroughJson11Dispatch() throws Exception {
        when(catalog.byCborSdkServiceId("Transcribe"))
                .thenReturn(Optional.of(descriptor("transcribe", "Transcribe")));
        when(json11Controller.dispatch(eq("transcribe"), eq("ListTranscriptionJobs"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull()))
                .thenReturn(Response.ok(JSON_MAPPER.createObjectNode()).build());

        Response response = route("Transcribe", "ListTranscriptionJobs", JSON_MAPPER.createObjectNode());

        assertEquals(200, response.getStatus());
        verify(json11Controller).dispatch(eq("transcribe"), eq("ListTranscriptionJobs"), any(),
                eq("us-east-1"), isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull());
    }

    @Test
    void cloudMapCreateDateIsTaggedInResponse() throws Exception {
        when(catalog.byCborSdkServiceId("Route53AutoNaming_v20170314"))
                .thenReturn(Optional.of(descriptor("servicediscovery", "Route53AutoNaming_v20170314")));
        JsonNode entity = JSON_MAPPER.readTree("""
                {"Namespaces": [{"Id": "ns-1", "CreateDate": 1700000000}]}
                """);
        when(json11Controller.dispatch(eq("servicediscovery"), eq("ListNamespaces"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull()))
                .thenReturn(Response.ok(entity).build());

        Response response = route("Route53AutoNaming_v20170314", "ListNamespaces",
                JSON_MAPPER.createObjectNode());

        assertEquals(200, response.getStatus());
        byte[] cborBytes = (byte[]) response.getEntity();
        int tagCount = 0;
        for (byte b : cborBytes) {
            if (b == (byte) 0xC1) {
                tagCount++;
            }
        }
        assertEquals(1, tagCount, "CreateDate must carry exactly one tag(1)");
        assertEquals(1700000000L, CBOR_MAPPER.readTree(cborBytes)
                .path("Namespaces").get(0).path("CreateDate").asLong());
    }

    @Test
    void textractBlobRequestReachesDispatchAsBinaryNode() throws Exception {
        when(catalog.byCborSdkServiceId("Textract"))
                .thenReturn(Optional.of(descriptor("textract", "Textract")));
        when(json11Controller.dispatch(eq("textract"), eq("DetectDocumentText"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull()))
                .thenReturn(Response.ok(JSON_MAPPER.createObjectNode()).build());
        byte[] document = {0x01, 0x02, (byte) 0xFF, 'a'};
        JsonNode request = JSON_MAPPER.createObjectNode();
        request.withObject("Document").put("Bytes", document);

        Response response = route("Textract", "DetectDocumentText", request);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(json11Controller).dispatch(eq("textract"), eq("DetectDocumentText"), captor.capture(),
                eq("us-east-1"), isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull());
        JsonNode bytes = captor.getValue().path("Document").path("Bytes");
        assertTrue(bytes.isBinary(), "a CBOR byte string must decode to a binary node");
        assertArrayEquals(document, bytes.binaryValue());
    }

    @Test
    void monitoringKeepsItsOwnHandler() throws Exception {
        when(catalog.byCborSdkServiceId("GraniteServiceVersion20100801"))
                .thenReturn(Optional.of(descriptor("monitoring", "GraniteServiceVersion20100801")));
        when(cloudWatchMetricsJsonHandler.handle(eq("ListMetrics"), any(), eq("us-east-1")))
                .thenReturn(Response.ok(JSON_MAPPER.createObjectNode()).build());

        Response response = route("GraniteServiceVersion20100801", "ListMetrics",
                JSON_MAPPER.createObjectNode());

        assertEquals(200, response.getStatus());
        verify(json11Controller, never()).dispatch(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void translateKeepsItsOwnCborHandler() throws Exception {
        when(catalog.byCborSdkServiceId("AWSShineFrontendService_20170701"))
                .thenReturn(Optional.of(descriptor("translate", "AWSShineFrontendService_20170701")));
        when(translateJsonHandler.handleCbor(eq("TranslateText"), any(), eq("us-east-1")))
                .thenReturn(Response.ok(JSON_MAPPER.createObjectNode()).build());

        Response response = route("AWSShineFrontendService_20170701", "TranslateText",
                JSON_MAPPER.createObjectNode());

        assertEquals(200, response.getStatus());
        verify(json11Controller, never()).dispatch(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownServiceStillReturns404() throws Exception {
        when(catalog.byCborSdkServiceId("NoSuchService")).thenReturn(Optional.empty());

        Response response = route("NoSuchService", "DoThing", JSON_MAPPER.createObjectNode());

        assertEquals(404, response.getStatus());
        verify(json11Controller, never()).dispatch(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unservedServiceKeyStillReturns404() throws Exception {
        when(catalog.byCborSdkServiceId("SimpleWorkflow_20120125"))
                .thenReturn(Optional.of(descriptor("swf", "SimpleWorkflow_20120125")));
        when(json11Controller.dispatch(eq("swf"), eq("ListDomains"), any(), eq("us-east-1"),
                isNull(), eq(ACCOUNT_ID), eq(BASE_URI), isNull())).thenReturn(null);

        Response response = route("SimpleWorkflow_20120125", "ListDomains", JSON_MAPPER.createObjectNode());

        assertEquals(404, response.getStatus(),
                "a resolved service no dispatcher serves must still 404, not 500");
    }
}
