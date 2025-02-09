/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.security.oauth2.server.config.custom;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/**
 * An authenticator used for OAuth 2.0 Client Authentication,
 * which authenticates the {@link PkceParameterNames#CODE_VERIFIER code_verifier} parameter.
 *
 *  This class mainly customized to provide support of pkce for refreshtoken
 *
 * @author Daniel Garnier-Moiroux
 * @author Joe Grandja
 * @see OAuth2ClientAuthenticationToken
 * @see OAuth2AuthorizationService
 * @since 0.2.3
 */
final class CustomCodeVerifierAuthenticator {
    private static final OAuth2TokenType AUTHORIZATION_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);
    private static final OAuth2TokenType REFRESH_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.REFRESH_TOKEN);
    private final OAuth2AuthorizationService authorizationService;

    CustomCodeVerifierAuthenticator(OAuth2AuthorizationService authorizationService) {
        Assert.notNull(authorizationService, "authorizationService cannot be null");
        this.authorizationService = authorizationService;
    }

    void authenticateRequired(OAuth2ClientAuthenticationToken clientAuthentication,
                              RegisteredClient registeredClient) {
        if (!authenticate(clientAuthentication, registeredClient)) {
            throwInvalidGrant(PkceParameterNames.CODE_VERIFIER);
        }
    }

    void authenticateIfAvailable(OAuth2ClientAuthenticationToken clientAuthentication,
                                 RegisteredClient registeredClient) {
        authenticate(clientAuthentication, registeredClient);
    }

    private boolean authenticate(OAuth2ClientAuthenticationToken clientAuthentication,
                                 RegisteredClient registeredClient) {

        Map<String, Object> parameters = clientAuthentication.getAdditionalParameters();
        if (!authorizationCodeGrant(parameters)) {
            return false;
        }
        OAuth2Authorization authorization = null;
        if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(
                parameters.get(OAuth2ParameterNames.GRANT_TYPE)) &&
                parameters.get(OAuth2ParameterNames.CODE) != null) {
            authorization = this.authorizationService.findByToken(
                    (String) parameters.get(OAuth2ParameterNames.CODE),
                    AUTHORIZATION_CODE_TOKEN_TYPE);
        }

        //CUSTOM CHANGE :: fetch OAuth2Authorization object using refresh_token
        if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(
                parameters.get(OAuth2ParameterNames.GRANT_TYPE)) &&
                parameters.get(OAuth2ParameterNames.REFRESH_TOKEN) != null) {
            authorization = this.authorizationService.findByToken(
                    (String) parameters.get(OAuth2ParameterNames.REFRESH_TOKEN),
                    REFRESH_TOKEN_TYPE);
        }
        if (authorization == null) {
            throwInvalidGrant(OAuth2ParameterNames.CODE);
        }

        OAuth2AuthorizationRequest authorizationRequest = authorization.getAttribute(
                OAuth2AuthorizationRequest.class.getName());

        String codeChallenge = (String) authorizationRequest.getAdditionalParameters()
                .get(PkceParameterNames.CODE_CHALLENGE);
        if (!StringUtils.hasText(codeChallenge)) {
            if (registeredClient.getClientSettings().isRequireProofKey()) {
                throwInvalidGrant(PkceParameterNames.CODE_CHALLENGE);
            } else {
                return false;
            }
        }

        String codeChallengeMethod = (String) authorizationRequest.getAdditionalParameters()
                .get(PkceParameterNames.CODE_CHALLENGE_METHOD);
        String codeVerifier = (String) parameters.get(PkceParameterNames.CODE_VERIFIER);
        if (!codeVerifierValid(codeVerifier, codeChallenge, codeChallengeMethod)) {
            throwInvalidGrant(PkceParameterNames.CODE_VERIFIER);
        }
        return true;
    }

    //CUSTOM CHANGE ::  Modified this method to allow refresh_token request as well for pkce
    private static boolean authorizationCodeGrant(Map<String, Object> parameters) {
        return (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(
                parameters.get(OAuth2ParameterNames.GRANT_TYPE)) &&
                parameters.get(OAuth2ParameterNames.CODE) != null) || (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(
                parameters.get(OAuth2ParameterNames.GRANT_TYPE)) &&
                parameters.get(OAuth2ParameterNames.REFRESH_TOKEN) != null);
    }

    private static boolean codeVerifierValid(String codeVerifier, String codeChallenge, String codeChallengeMethod) {
        if (!StringUtils.hasText(codeVerifier)) {
            return false;
        } else if ("S256".equals(codeChallengeMethod)) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                String encodedVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
                return encodedVerifier.equals(codeChallenge);
            } catch (NoSuchAlgorithmException ex) {
                // It is unlikely that SHA-256 is not available on the server. If it is not available,
                // there will likely be bigger issues as well. We default to SERVER_ERROR.
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
            }
        }
        return false;
    }

    private static void throwInvalidGrant(String parameterName) {
        OAuth2Error error = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT,
                "Client authentication failed: " + parameterName,
                null
        );
        throw new OAuth2AuthenticationException(error);
    }

}
