package com.rbc.fogwall.scmapi;

import tools.jackson.databind.JsonNode;

/**
 * Parsed shape of a GraphQL-over-HTTP request body: {@code {"query": ..., "variables": ..., "operationName": ...}}.
 *
 * @param query the raw GraphQL document text
 * @param variables the request's {@code variables} object, or {@code null} if absent
 * @param operationName the client-supplied {@code operationName}, or {@code null} if absent — never used for security
 *     decisions since it is provider/client-specific (e.g. {@code gh}'s own naming) and does not identify the schema
 *     mutation field being invoked
 */
public record ScmApiGraphQlRequest(String query, JsonNode variables, String operationName) {}
