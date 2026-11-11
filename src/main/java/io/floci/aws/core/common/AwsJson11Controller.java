package io.floci.aws.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

/**
 * Generic dispatcher for all AWS services that use the application/x-amz-json-1.1 protocol.
 * Routes requests to the appropriate service handler based on the X-Amz-Target header prefix.
 * <p>
 * Currently supported services:
 * - SSM (AmazonSSM.*)
 * - EventBridge (AmazonEventBridge.*)
 * - CloudWatch Logs (Logs_20140328.*)
 */
@Path("/")
public class AwsJson11Controller {

    public static final String CONTENT_TYPE_AWS_JSON_1_1 = "application/x-amz-json-1.1";
    private static final Logger LOG = Logger.getLogger(AwsJson11Controller.class);

    private final ObjectMapper objectMapper;
    private final ObjectReader strictBodyReader;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;
    private final JsonServiceDispatcher dispatcher;

    @Inject
    public AwsJson11Controller(ObjectMapper objectMapper, ResolvedServiceCatalog catalog,
                               RegionResolver regionResolver,
                               JsonServiceDispatcher dispatcher) {
        this.objectMapper = objectMapper;
        this.strictBodyReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.catalog = catalog;
        this.regionResolver = regionResolver;
        this.dispatcher = dispatcher;
    }

    @POST
    @Consumes(CONTENT_TYPE_AWS_JSON_1_1)
    @Produces(CONTENT_TYPE_AWS_JSON_1_1)
    public Response handle(
            @HeaderParam("X-Amz-Target") String target,
            @Context HttpHeaders httpHeaders,
            @Context UriInfo uriInfo,
            String body) {

        if (target == null) {
            return JsonErrorResponseUtils.createMissingTargetErrorResponse();
        }

        ServiceCatalog.TargetMatch targetMatch = catalog.matchTarget(target).orElse(null);
        if (targetMatch == null) {
            return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
        }

        String serviceKey = targetMatch.descriptor().externalKey();
        String action = targetMatch.action();
        LOG.infov("AwsJson11Controller {0} action: {1}", serviceKey, action);

        JsonNode request;
        try {
            // No payload is how an operation without input goes over the wire, so it reads as {}.
            request = body == null || body.isBlank()
                    ? objectMapper.createObjectNode()
                    : strictBodyReader.readTree(body);
        } catch (JsonProcessingException e) {
            return JsonErrorResponseUtils.createSerializationErrorResponse();
        }
        // Input is always a structure: a bare null, array or scalar parses but is still a
        // client serialization error rather than something every handler must guard against.
        if (!request.isObject()) {
            return JsonErrorResponseUtils.createSerializationErrorResponse();
        }

        try {
            String region = regionResolver.getRegion();
            String authorization = httpHeaders.getHeaderString("Authorization");
            if (authorization == null || authorization.isBlank()) {
                authorization = IamEnforcementFilter.requestAuthorization(null, uriInfo.getQueryParameters());
            }

            Response delegated = dispatcher.dispatch(serviceKey, action, request, region,
                    authorization, uriInfo.getBaseUri(), targetMatch.prefix());
            // catalog.matchTarget is protocol-agnostic: a JSON 1.0 target
            // (e.g. DynamoDB_20120810.*) can match here under @Consumes json-1.1.
            // Return the AWS-style unknown-operation error rather than null.
            if (delegated == null) {
                return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
            }
            return delegated;
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Error processing %s request", serviceKey);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

}
