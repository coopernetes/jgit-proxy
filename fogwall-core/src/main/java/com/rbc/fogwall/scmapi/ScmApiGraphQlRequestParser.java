package com.rbc.fogwall.scmapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses the raw JSON body of a GraphQL-over-HTTP request into a {@link ScmApiGraphQlRequest}. */
public final class ScmApiGraphQlRequestParser {

    private static final JsonMapper MAPPER = new JsonMapper();

    private ScmApiGraphQlRequestParser() {}

    /** @throws GraphQlParseException if {@code rawBody} is not valid JSON, or has no textual {@code query} field */
    public static ScmApiGraphQlRequest parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new GraphQlParseException("GraphQL request body is not valid JSON", e);
        }
        if (root == null) {
            throw new GraphQlParseException("GraphQL request body is empty");
        }
        JsonNode queryNode = root.get("query");
        if (queryNode == null || !queryNode.isString()) {
            throw new GraphQlParseException("GraphQL request body is missing a textual 'query' field");
        }
        JsonNode variables = root.get("variables");
        JsonNode operationNameNode = root.get("operationName");
        String operationName =
                operationNameNode != null && operationNameNode.isString() ? operationNameNode.asString() : null;
        return new ScmApiGraphQlRequest(queryNode.asString(), variables, operationName);
    }
}
