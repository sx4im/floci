package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.services.cloudhsmv2.CloudHsmV2JsonHandler;
import io.floci.aws.services.cloudmap.CloudMapHandler;
import io.floci.aws.services.codebuild.CodeBuildJsonHandler;
import io.floci.aws.services.codedeploy.CodeDeployJsonHandler;
import io.floci.aws.services.codepipeline.CodePipelineJsonHandler;
import io.floci.aws.services.comprehend.ComprehendJsonHandler;
import io.floci.aws.services.datasync.DataSyncJsonHandler;
import io.floci.aws.services.memorydb.MemoryDbHandler;
import io.floci.aws.services.networkfirewall.NetworkFirewallJsonHandler;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingJsonHandler;
import io.floci.aws.services.servicecatalog.ServiceCatalogJsonHandler;
import io.floci.aws.services.textract.TextractJsonHandler;
import io.floci.aws.services.transcribe.TranscribeJsonHandler;
import io.floci.aws.services.translate.TranslateJsonHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for generic smithy-rpc-v2-cbor routing (floci-io/floci#7983): every
 * JSON-protocol service Floci serves must answer over CBOR, routed through the
 * same dispatch JSON 1.1 requests already go through, instead of one switch arm
 * per service. No Quarkus boot: the controller and the shared dispatcher are
 * built directly with mocked collaborators and the CBOR bytes are decoded with
 * a plain Jackson CBOR mapper. The end-to-end wire shape is covered by
 * {@code SmithyRpcV2RoutingIntegrationTest} in CI.
 */
class AwsJsonCborGenericRoutingTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final URI BASE_URI = URI.create("http://localhost:4566/");
    private static final String ACCOUNT_ID = "123456789012";

    /** An empty CBOR map, the same {@code 0xa0} body the issue's curl repro sends. */
    private static final byte[] EMPTY_CBOR_MAP = {(byte) 0xA0};

    /** CBOR tag 1, the single byte {@code 0xC1} preceding a timestamp value. */
    private static final byte CBOR_TAG_1 = (byte) 0xC1;

    private static ServiceDescriptor descriptorFor(String externalKey, String targetPrefix) {
        return new ServiceDescriptor(externalKey, externalKey, true, true,
                externalKey, "memory", 5000L, null, ServiceProtocol.JSON,
                Set.of(ServiceProtocol.JSON), Set.of(targetPrefix),
                Set.of(externalKey), Set.of(), Set.of(), Set.of());
    }

    private static HttpHeaders cborHeaders() {
        HttpHeaders headers = Mockito.mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenReturn("application/cbor");
        return headers;
    }

    private static UriInfo uriInfo() {
        UriInfo uriInfo = Mockito.mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(BASE_URI);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    private static ResolvedServiceCatalog catalogReturning(ServiceDescriptor descriptor) {
        ResolvedServiceCatalog catalog = Mockito.mock(ResolvedServiceCatalog.class);
        when(catalog.byCborSdkServiceId(anyString())).thenReturn(Optional.of(descriptor));
        return catalog;
    }

    private static RegionResolver regionResolver() {
        RegionResolver resolver = Mockito.mock(RegionResolver.class);
        when(resolver.resolveRegion(any())).thenReturn("us-east-1");
        when(resolver.getRegion()).thenReturn("us-east-1");
        when(resolver.getAccountId()).thenReturn(ACCOUNT_ID);
        return resolver;
    }

    private static AwsJsonCborController controllerWith(ResolvedServiceCatalog catalog,
                                                       JsonServiceDispatcher dispatcher) {
        return new AwsJsonCborController(JSON_MAPPER, catalog, regionResolver(),
                null, null, null, null, null, null, null, null, null, dispatcher);
    }

    private static Response okResponse(JsonNode entity) {
        return Response.ok(entity).build();
    }

    private static int countTag1(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) {
            if (b == CBOR_TAG_1) {
                count++;
            }
        }
        return count;
    }

    @Test
    void codeBuildRoutesOverCbor() throws Exception {
        JsonNode projects = JSON_MAPPER.readTree("{\"projects\": [\"demo\"]}");
        JsonServiceDispatcher dispatcher = Mockito.mock(JsonServiceDispatcher.class);
        when(dispatcher.dispatch(eq("codebuild"), eq("ListProjects"), any(), eq("us-east-1"),
                any(), eq(BASE_URI), eq("CodeBuild_20161006.")))
                .thenReturn(okResponse(projects));
        AwsJsonCborController controller = controllerWith(
                catalogReturning(descriptorFor("codebuild", "CodeBuild_20161006.")), dispatcher);

        Response response = controller.handleSmithyRpcV2Cbor(
                "CodeBuild_20161006", "ListProjects", cborHeaders(), uriInfo(), EMPTY_CBOR_MAP);

        assertEquals(200, response.getStatus());
        JsonNode decoded = CBOR_MAPPER.readTree((byte[]) response.getEntity());
        assertEquals("demo", decoded.path("projects").path(0).asText());
        verify(dispatcher).dispatch(eq("codebuild"), eq("ListProjects"), any(), eq("us-east-1"),
                any(), eq(BASE_URI), eq("CodeBuild_20161006."));
    }

    @Test
    void unknownServiceStillReturns404() throws Exception {
        ResolvedServiceCatalog catalog = Mockito.mock(ResolvedServiceCatalog.class);
        when(catalog.byCborSdkServiceId(anyString())).thenReturn(Optional.empty());
        AwsJsonCborController controller = controllerWith(catalog,
                Mockito.mock(JsonServiceDispatcher.class));

        Response response = controller.handleSmithyRpcV2Cbor(
                "NoSuchService", "DoThing", cborHeaders(), uriInfo(), EMPTY_CBOR_MAP);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unservedServiceStillReturns404() throws Exception {
        JsonServiceDispatcher dispatcher = Mockito.mock(JsonServiceDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(null);
        AwsJsonCborController controller = controllerWith(
                catalogReturning(descriptorFor("swf", "SimpleWorkflowService.")), dispatcher);

        Response response = controller.handleSmithyRpcV2Cbor(
                "SimpleWorkflowService", "ListDomains", cborHeaders(), uriInfo(), EMPTY_CBOR_MAP);

        // A JSON 1.0-only service has no JSON 1.1 dispatch: still a 404, not a 500.
        assertEquals(404, response.getStatus());
    }

    @Test
    void cborBlobRequestReachesHandlerAsBase64() throws Exception {
        byte[] document = {0x00, 0x01, (byte) 0xFF, 'a'};
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document").put("Bytes", document);
        AtomicReference<JsonNode> seenByHandler = new AtomicReference<>();
        JsonServiceDispatcher dispatcher = Mockito.mock(JsonServiceDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    seenByHandler.set(invocation.getArgument(2));
                    return okResponse(JSON_MAPPER.readTree("{\"Blocks\": []}"));
                });
        AwsJsonCborController controller = controllerWith(
                catalogReturning(descriptorFor("textract", "Textract.")), dispatcher);

        Response response = controller.handleSmithyRpcV2Cbor(
                "Textract", "DetectDocumentText", cborHeaders(), uriInfo(),
                CBOR_MAPPER.writeValueAsBytes(request));

        assertEquals(200, response.getStatus());
        JsonNode bytes = seenByHandler.get().path("Document").path("Bytes");
        assertTrue(bytes.isTextual(), "a CBOR byte string must reach the handler as base64 text");
        assertEquals(Base64.getEncoder().encodeToString(document), bytes.textValue());
    }

    @Test
    void binaryNodeResponseIsWrittenAsCborByteString() throws Exception {
        byte[] blob = {0x00, 0x01, (byte) 0xFF, 'a', 'b'};
        ObjectNode node = JSON_MAPPER.createObjectNode();
        node.putObject("TranslatedDocument").put("Content", blob);

        for (byte[] encoded : new byte[][] {
                AwsJsonCborController.nodeToSmithyCbor(node),
                AwsJsonCborController.nodeToLegacyCbor(node)}) {
            JsonNode content = CBOR_MAPPER.readTree(encoded).path("TranslatedDocument").path("Content");
            assertTrue(content.isBinary(), "blob must be a CBOR byte string (major type 2), not text");
            assertArrayEquals(blob, content.binaryValue());
        }
    }

    @Test
    void translateDocumentResponseBlobIsWrittenAsByteString() throws Exception {
        byte[] document = {'H', 'e', 'l', 'l', 'o', ' ', (byte) 0xC3, (byte) 0xA9, 0x00, (byte) 0xFF};
        ObjectNode translated = JSON_MAPPER.createObjectNode();
        translated.putObject("TranslatedDocument")
                .put("Content", Base64.getEncoder().encodeToString(document))
                .put("ContentType", "text/plain");
        JsonServiceDispatcher dispatcher = Mockito.mock(JsonServiceDispatcher.class);
        when(dispatcher.dispatch(eq("translate"), eq("TranslateDocument"), any(), anyString(),
                any(), any(), anyString())).thenReturn(okResponse(translated));
        AwsJsonCborController controller = controllerWith(
                catalogReturning(descriptorFor("translate", "AWSShineFrontendService_20170701.")),
                dispatcher);
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.putObject("Document")
                .put("Content", document)
                .put("ContentType", "text/plain");
        request.put("SourceLanguageCode", "en");
        request.put("TargetLanguageCode", "es");

        Response response = controller.handleSmithyRpcV2Cbor(
                "AWSShineFrontendService_20170701", "TranslateDocument", cborHeaders(), uriInfo(),
                CBOR_MAPPER.writeValueAsBytes(request));

        assertEquals(200, response.getStatus());
        JsonNode content = CBOR_MAPPER.readTree((byte[]) response.getEntity())
                .path("TranslatedDocument").path("Content");
        assertTrue(content.isBinary(), "TranslatedDocument.Content must come back as a CBOR byte string");
        assertArrayEquals(document, content.binaryValue());
    }

    @Test
    void declaredTimestampMembersAreTaggedInResponses() throws Exception {
        ObjectNode dataSyncResponse = JSON_MAPPER.createObjectNode();
        dataSyncResponse.put("CreationTime", 1700000000);
        JsonServiceDispatcher dispatcher = Mockito.mock(JsonServiceDispatcher.class);
        when(dispatcher.dispatch(eq("datasync"), eq("ListTasks"), any(), anyString(),
                any(), any(), anyString())).thenReturn(okResponse(dataSyncResponse));
        AwsJsonCborController dataSync = controllerWith(
                catalogReturning(descriptorFor("datasync", "FmrsService.")), dispatcher);

        Response dataSyncResult = dataSync.handleSmithyRpcV2Cbor(
                "FmrsService", "ListTasks", cborHeaders(), uriInfo(), EMPTY_CBOR_MAP);

        assertEquals(200, dataSyncResult.getStatus());
        assertEquals(1, countTag1((byte[]) dataSyncResult.getEntity()),
                "datasync CreationTime must be tagged exactly once");

        ObjectNode pipelineResponse = JSON_MAPPER.createObjectNode();
        ObjectNode pipeline = pipelineResponse.putArray("pipelines").addObject();
        pipeline.put("name", "demo");
        pipeline.put("created", 1700000000.0);
        pipeline.put("updated", 1700000001.0);
        when(dispatcher.dispatch(eq("codepipeline"), eq("ListPipelines"), any(), anyString(),
                any(), any(), anyString())).thenReturn(okResponse(pipelineResponse));
        AwsJsonCborController codePipeline = controllerWith(
                catalogReturning(descriptorFor("codepipeline", "CodePipeline_20150709.")), dispatcher);

        Response pipelineResult = codePipeline.handleSmithyRpcV2Cbor(
                "CodePipeline_20150709", "ListPipelines", cborHeaders(), uriInfo(), EMPTY_CBOR_MAP);

        assertEquals(200, pipelineResult.getStatus());
        assertEquals(2, countTag1((byte[]) pipelineResult.getEntity()),
                "codepipeline created and updated must each be tagged exactly once");
    }

    @Test
    void timestampMemberTableMatchesDeclaredServices() {
        assertEquals(Map.of(
                        "codepipeline", Set.of("created", "updated"),
                        "datasync", Set.of("CreationTime"),
                        "servicecatalog", Set.of("CreatedTime"),
                        "servicediscovery", Set.of("CreateDate"),
                        "transcribe", Set.of("CreationTime", "StartTime", "CompletionTime")),
                AwsJsonCborController.TIMESTAMP_MEMBERS_BY_SERVICE);
    }

    @Test
    void dispatcherRoutesEachJsonService() throws Exception {
        CloudHsmV2JsonHandler cloudHsm = Mockito.mock(CloudHsmV2JsonHandler.class);
        CloudMapHandler cloudMap = Mockito.mock(CloudMapHandler.class);
        CodeBuildJsonHandler codeBuild = Mockito.mock(CodeBuildJsonHandler.class);
        CodeDeployJsonHandler codeDeploy = Mockito.mock(CodeDeployJsonHandler.class);
        CodePipelineJsonHandler codePipeline = Mockito.mock(CodePipelineJsonHandler.class);
        ComprehendJsonHandler comprehend = Mockito.mock(ComprehendJsonHandler.class);
        DataSyncJsonHandler dataSync = Mockito.mock(DataSyncJsonHandler.class);
        MemoryDbHandler memoryDb = Mockito.mock(MemoryDbHandler.class);
        NetworkFirewallJsonHandler networkFirewall = Mockito.mock(NetworkFirewallJsonHandler.class);
        ResourceGroupsTaggingJsonHandler tagging = Mockito.mock(ResourceGroupsTaggingJsonHandler.class);
        ServiceCatalogJsonHandler serviceCatalog = Mockito.mock(ServiceCatalogJsonHandler.class);
        TextractJsonHandler textract = Mockito.mock(TextractJsonHandler.class);
        TranscribeJsonHandler transcribe = Mockito.mock(TranscribeJsonHandler.class);
        TranslateJsonHandler translate = Mockito.mock(TranslateJsonHandler.class);
        RegionResolver resolver = regionResolver();
        JsonServiceDispatcher dispatcher = new JsonServiceDispatcher(
                null,
                null,
                cloudMap,
                null,
                memoryDb,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                tagging,
                codeBuild,
                codeDeploy,
                codePipeline,
                null,
                null,
                null,
                textract,
                comprehend,
                null,
                null,
                transcribe,
                translate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dataSync,
                networkFirewall,
                serviceCatalog,
                null,
                null,
                cloudHsm,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resolver
        );

        dispatchAndCheck(dispatcher, cloudHsm, "cloudhsmv2", "DescribeClusters");
        dispatchAndCheck(dispatcher, cloudMap, "servicediscovery", "ListNamespaces");
        dispatchAndCheck(dispatcher, codeBuild, "codebuild", "ListProjects");
        dispatchAndCheck(dispatcher, codeDeploy, "codedeploy", "ListApplications");
        dispatchAndCheck(dispatcher, codePipeline, "codepipeline", "ListPipelines");
        dispatchAndCheck(dispatcher, comprehend, "comprehend", "DetectDominantLanguage");
        dispatchAndCheck(dispatcher, dataSync, "datasync", "ListTasks");
        dispatchAndCheck(dispatcher, memoryDb, "memorydb", "DescribeClusters");
        dispatchAndCheck(dispatcher, networkFirewall, "network-firewall", "ListFirewalls");
        dispatchAndCheck(dispatcher, tagging, "tagging", "GetResources");
        dispatchAndCheck(dispatcher, serviceCatalog, "servicecatalog", "ListPortfolios");
        dispatchAndCheck(dispatcher, textract, "textract", "DetectDocumentText");
        dispatchAndCheck(dispatcher, transcribe, "transcribe", "ListTranscriptionJobs");
        dispatchAndCheck(dispatcher, translate, "translate", "TranslateText");

        // The shared dispatch serves JSON 1.1 services only: a JSON 1.0 service
        // (DynamoDB, served by the CBOR controller's own arm) is not its business.
        JsonNode request = JSON_MAPPER.createObjectNode();
        assertNull(dispatcher.dispatch("dynamodb", "ListTables", request, "us-east-1",
                "sig", BASE_URI, "DynamoDB_20120810."));
        assertNull(dispatcher.dispatch("nosuch", "DoThing", request, "us-east-1",
                "sig", BASE_URI, "NoSuch."));
    }

    /**
     * Stubs one handler to answer a marker response, dispatches to it, and verifies
     * the handler was called with the operation, request, region and (where the
     * handler takes one) account the JSON controller always passed.
     */
    private void dispatchAndCheck(JsonServiceDispatcher dispatcher, Object handler,
                                  String serviceKey, String operation) throws Exception {
        JsonNode request = JSON_MAPPER.createObjectNode();
        JsonNode marker = JSON_MAPPER.readTree("{\"ok\": \"" + serviceKey + "\"}");
        Response markerResponse = okResponse(marker);
        if (handler instanceof CloudHsmV2JsonHandler cloudHsm) {
            when(cloudHsm.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof CloudMapHandler cloudMap) {
            when(cloudMap.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof CodeBuildJsonHandler codeBuild) {
            when(codeBuild.handle(eq(operation), any(), eq("us-east-1"), eq(ACCOUNT_ID)))
                    .thenReturn(markerResponse);
        } else if (handler instanceof CodeDeployJsonHandler codeDeploy) {
            when(codeDeploy.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof CodePipelineJsonHandler codePipeline) {
            when(codePipeline.handle(eq(operation), any(), eq("us-east-1"), eq(ACCOUNT_ID)))
                    .thenReturn(markerResponse);
        } else if (handler instanceof ComprehendJsonHandler comprehend) {
            when(comprehend.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof DataSyncJsonHandler dataSync) {
            when(dataSync.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof MemoryDbHandler memoryDb) {
            when(memoryDb.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof NetworkFirewallJsonHandler networkFirewall) {
            when(networkFirewall.handle(eq(operation), any(), eq("us-east-1"), eq(ACCOUNT_ID)))
                    .thenReturn(markerResponse);
        } else if (handler instanceof ResourceGroupsTaggingJsonHandler tagging) {
            when(tagging.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof ServiceCatalogJsonHandler serviceCatalog) {
            when(serviceCatalog.handle(eq(operation), any(), eq("us-east-1"), eq(ACCOUNT_ID)))
                    .thenReturn(markerResponse);
        } else if (handler instanceof TextractJsonHandler textract) {
            when(textract.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else if (handler instanceof TranscribeJsonHandler transcribe) {
            when(transcribe.handle(eq(operation), any())).thenReturn(markerResponse);
        } else if (handler instanceof TranslateJsonHandler translate) {
            when(translate.handle(eq(operation), any(), eq("us-east-1"))).thenReturn(markerResponse);
        } else {
            throw new IllegalArgumentException("no stub arm for " + handler.getClass());
        }

        Response response = dispatcher.dispatch(serviceKey, operation, request, "us-east-1",
                "sig", BASE_URI, "Prefix.");

        assertEquals(markerResponse, response, serviceKey + " must dispatch to its handler");
    }
}
