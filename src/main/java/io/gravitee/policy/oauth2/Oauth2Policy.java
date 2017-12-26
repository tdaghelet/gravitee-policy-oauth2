/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.oauth2.configuration.OAuth2PolicyConfiguration;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.oauth2.api.OAuth2Resource;
import io.gravitee.resource.oauth2.api.OAuth2Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Oauth2Policy {

    private final Logger logger = LoggerFactory.getLogger(Oauth2Policy.class);

    static final String BEARER_AUTHORIZATION_TYPE = "Bearer";
    static final String OAUTH_PAYLOAD_SCOPE_NODE = "scope";
    static final String OAUTH_PAYLOAD_CLIENT_ID_NODE = "client_id";

    static final String CONTEXT_ATTRIBUTE_PREFIX = "oauth.";
    static final String CONTEXT_ATTRIBUTE_OAUTH_PAYLOAD = CONTEXT_ATTRIBUTE_PREFIX + "payload";
    static final String CONTEXT_ATTRIBUTE_OAUTH_ACCESS_TOKEN = CONTEXT_ATTRIBUTE_PREFIX + "access_token";
    static final String CONTEXT_ATTRIBUTE_CLIENT_ID = CONTEXT_ATTRIBUTE_PREFIX + "client_id";

    static final String DEFAULT_SCOPE_SEPARATOR = " ";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private OAuth2PolicyConfiguration oAuth2PolicyConfiguration;

    public Oauth2Policy (OAuth2PolicyConfiguration oAuth2PolicyConfiguration) {
        this.oAuth2PolicyConfiguration = oAuth2PolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        logger.debug("Read access_token from request {}", request.id());

        OAuth2Resource oauth2 = executionContext.getComponent(ResourceManager.class).getResource(
                oAuth2PolicyConfiguration.getOauthResource(), OAuth2Resource.class);

        if (oauth2 == null) {
            policyChain.failWith(PolicyResult.failure(HttpStatusCode.UNAUTHORIZED_401,
                    "No OAuth authorization server has been configured"));
            return;
        }

        List<String> authorizationHeaders = request.headers().get(HttpHeaders.AUTHORIZATION);

        if (authorizationHeaders == null || authorizationHeaders.isEmpty()) {
            sendError(response, policyChain, "invalid_request", "No OAuth authorization header was supplied");
            return;
        }

        Optional<String> optionalHeaderAccessToken = authorizationHeaders
                .stream()
                .filter(h -> StringUtils.startsWithIgnoreCase(h, BEARER_AUTHORIZATION_TYPE))
                .findFirst();
        if (!optionalHeaderAccessToken.isPresent()) {
            sendError(response, policyChain, "invalid_request", "No OAuth authorization header was supplied");
            return;
        }

        String accessToken = optionalHeaderAccessToken.get().substring(BEARER_AUTHORIZATION_TYPE.length()).trim();
        if (accessToken.isEmpty()) {
            sendError(response, policyChain, "invalid_request", "No OAuth access token was supplied");
            return;
        }

        // Set access_token in context
        executionContext.setAttribute(CONTEXT_ATTRIBUTE_OAUTH_ACCESS_TOKEN, accessToken);

        // Validate access token
        oauth2.introspect(accessToken, handleResponse(policyChain, request, response, executionContext));
    }

    Handler<OAuth2Response> handleResponse(PolicyChain policyChain, Request request, Response response, ExecutionContext executionContext) {
        return oauth2response -> {
            if (oauth2response.isSuccess()) {
                JsonNode oauthResponseNode = readPayload(oauth2response.getPayload());

                if (oauthResponseNode == null) {
                    sendError(response, policyChain, "server_error", "Invalid response from authorization server");
                    return;
                }

                // Extract client_id
                String clientId = oauthResponseNode.path(OAUTH_PAYLOAD_CLIENT_ID_NODE).asText();
                if (clientId == null || clientId.trim().isEmpty()) {
                    sendError(response, policyChain, "invalid_client", "No client_id was supplied");
                    return;
                }

                executionContext.setAttribute(CONTEXT_ATTRIBUTE_CLIENT_ID, clientId);

                // Check required scopes to access the resource
                if (oAuth2PolicyConfiguration.isCheckRequiredScopes()) {
                    if (! hasRequiredScopes(oauthResponseNode, oAuth2PolicyConfiguration.getRequiredScopes())) {
                        sendError(response, policyChain, "insufficient_scope",
                                "The request requires higher privileges than provided by the access token.");
                        return;
                    }
                }

                // Store OAuth2 payload into execution context if required
                if (oAuth2PolicyConfiguration.isExtractPayload()) {
                    executionContext.setAttribute(CONTEXT_ATTRIBUTE_OAUTH_PAYLOAD, oauth2response.getPayload());
                }

                // Continue chaining
                policyChain.doNext(request, response);
            } else {
                response.headers().add(HttpHeaders.WWW_AUTHENTICATE, BEARER_AUTHORIZATION_TYPE + " realm=gravitee.io ");

                if (oauth2response.getThrowable() == null) {
                    policyChain.failWith(PolicyResult.failure(HttpStatusCode.UNAUTHORIZED_401,
                            oauth2response.getPayload(), MediaType.APPLICATION_JSON));
                } else {
                    policyChain.failWith(PolicyResult.failure(HttpStatusCode.SERVICE_UNAVAILABLE_503,
                            "temporarily_unavailable"));
                }
            }
        };
    }

    /**
     * As per https://tools.ietf.org/html/rfc6750#page-7:
     *
     *      HTTP/1.1 401 Unauthorized
     *      WWW-Authenticate: Bearer realm="example",
     *      error="invalid_token",
     *      error_description="The access token expired"
     */
    private void sendError(Response response, PolicyChain policyChain, String error, String description) {
        String headerValue = BEARER_AUTHORIZATION_TYPE +
                " realm=\"gravitee.io\"," +
                " error=\"" + error + "\"," +
                " error_description=\"" + description + "\"";
        response.headers().add(HttpHeaders.WWW_AUTHENTICATE, headerValue);
        policyChain.failWith(PolicyResult.failure(HttpStatusCode.UNAUTHORIZED_401, null));
    }

    private JsonNode readPayload(String oauthPayload) {
        try {
            return MAPPER.readTree(oauthPayload);
        } catch (IOException ioe) {
            logger.error("Unable to check required scope from introspection endpoint payload: {}", oauthPayload);
            return null;
        }
    }

    static boolean hasRequiredScopes(JsonNode oauthResponseNode, List<String> requiredScopes) {
        if (requiredScopes == null) {
            return true;
        }

        JsonNode scopesNode = oauthResponseNode.path(OAUTH_PAYLOAD_SCOPE_NODE);

        List<String> scopes;
        if (scopesNode instanceof ArrayNode) {
            Iterator<JsonNode> scopeIterator = scopesNode.elements();
            scopes = new ArrayList<>(scopesNode.size());
            List<String> finalScopes = scopes;
            scopeIterator.forEachRemaining(jsonNode -> finalScopes.add(jsonNode.asText()));
        } else {
            scopes = Arrays.asList(scopesNode.asText().split(DEFAULT_SCOPE_SEPARATOR));
        }

        return scopes.containsAll(requiredScopes);
    }
}
