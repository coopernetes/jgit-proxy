package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.config.BlockConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.validation.SecretScanCheck;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProposalContentInspectorTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static BlockConfig blocking(List<String> literals, List<String> patterns) {
        return BlockConfig.builder()
                .literals(literals)
                .patterns(patterns.stream().map(Pattern::compile).toList())
                .build();
    }

    private static ProposalContentInspector inspector(BlockConfig block, SecretScanConfig secretScan) {
        return inspector(block, secretScan, ContentPatternConfig.defaultConfig());
    }

    private static ProposalContentInspector inspector(
            BlockConfig block, SecretScanConfig secretScan, ContentPatternConfig contentPatterns) {
        return new ProposalContentInspector(
                () -> block, () -> secretScan, new SecretScanCheck(secretScan), () -> contentPatterns);
    }

    private static ContentPatternConfig bundles(String... names) {
        return ContentPatternConfig.builder()
                .enabled(true)
                .bundles(List.of(names))
                .build();
    }

    private static SecretScanConfig secretScanning(boolean enabled) {
        return SecretScanConfig.builder().enabled(enabled).build();
    }

    private static List<ProposalContent> fields(String... pairs) {
        var content = new ArrayList<ProposalContent>();
        for (int i = 0; i < pairs.length; i += 2) {
            content.add(new ProposalContent(pairs[i], pairs[i + 1]));
        }
        return content;
    }

    private static ProposalPayload payload(String json) {
        return ProposalPayload.of(json.getBytes(StandardCharsets.UTF_8), MAPPER.readTree(json));
    }

    private static String body(String token) {
        return "{\"description\":\"here is my token " + token + " ok\"}";
    }

    /** High entropy on purpose: gitleaks discards low-entropy matches as false positives. */
    private static String realisticToken() {
        var random = new SecureRandom();
        var body = new StringBuilder();
        while (body.length() < 36) {
            byte[] bytes = new byte[48];
            random.nextBytes(bytes);
            body.append(Base64.getEncoder().encodeToString(bytes).replaceAll("[^a-zA-Z0-9]", ""));
        }
        return "ghp_" + body.substring(0, 36);
    }

    @Test
    void passesCleanContent() {
        var result = inspector(blocking(List.of("secret-host"), List.of()), secretScanning(false))
                .inspect(
                        fields("title", "Add a README", "body", "no issue"),
                        payload("{\"title\":\"Add a README\",\"body\":\"no issue\"}"));
        assertTrue(result.isEmpty(), () -> "expected clean, got " + result);
    }

    @Test
    void catchesABlockedLiteralInAnyField() {
        var result = inspector(blocking(List.of("internal.corp.example.com"), List.of()), secretScanning(false))
                .inspect(
                        fields("body", "see internal.corp.example.com for details"),
                        payload("{\"body\":\"see internal.corp.example.com for details\"}"));
        assertEquals(1, result.size(), result::toString);
        assertTrue(result.get(0).contains("internal.corp.example.com"), result.get(0));
        assertTrue(result.get(0).contains("body"), "the finding should name the field it came from: " + result.get(0));
    }

    @Test
    void catchesABlockedPattern() {
        var result = inspector(
                        blocking(List.of(), List.of("(?i)https?://[a-z0-9.-]*\\.corp\\.example\\.com\\b")),
                        secretScanning(false))
                .inspect(
                        fields("description", "docs at https://wiki.corp.example.com/x"),
                        payload("{\"description\":\"docs at https://wiki.corp.example.com/x\"}"));
        assertEquals(1, result.size(), result::toString);
    }

    /**
     * The whole point of the feature: a contributor blocked from pushing a secret must not be able to publish it in a
     * pull request description instead.
     */
    @Test
    void catchesASecretInAProposalBody() {
        String token = realisticToken();
        var result = inspector(blocking(List.of(), List.of()), secretScanning(true))
                .inspect(fields("description", "here is my token " + token + " ok"), payload(body(token)));
        assertFalse(result.isEmpty(), "a secret in a proposal body must be caught");
        assertTrue(result.get(0).toLowerCase().contains("secret"), result.get(0));
    }

    /**
     * The evasion the raw/decoded split exists for: escaped as JSON the token matches nothing as raw text, but the
     * decoded reading of the same document shows it plainly.
     */
    @Test
    void catchesASecretHiddenByJsonEscaping() {
        String token = realisticToken();
        String escaped = "ghp_\\u00" + Integer.toHexString(token.charAt(4)) + token.substring(5);
        var result = inspector(blocking(List.of(), List.of()), secretScanning(true))
                .inspect(List.of(), payload("{\"description\":\"" + escaped + "\"}"));
        assertFalse(result.isEmpty(), "an escaped secret must still be caught via the decoded reading");
    }

    /** A field no extractor names is still covered, because the whole payload is scanned. */
    @Test
    void catchesABlockedTermInAnUnenumeratedField() {
        var result = inspector(blocking(List.of("internal.corp.example.com"), List.of()), secretScanning(false))
                .inspect(List.of(), payload("{\"some_future_field\":\"internal.corp.example.com\"}"));
        assertEquals(1, result.size(), result::toString);
    }

    @Test
    void secretScanningDisabled_doesNotScan() {
        String token = realisticToken();
        var result = inspector(blocking(List.of(), List.of()), secretScanning(false))
                .inspect(fields("description", "here is my token " + token + " ok"), payload(body(token)));
        assertTrue(result.isEmpty(), result::toString);
    }

    /**
     * GraphQL lets a caller inline argument values in the query text instead of passing variables, so the field
     * extractor — which reads {@code variables.input} — sees nothing at all. The payload walk covers it, because the
     * query is itself a string in the request body.
     */
    @Test
    void catchesASecretInlinedInTheGraphQlQueryText() {
        String token = realisticToken();
        String body = "{\"query\":\"mutation{createIssue(input:{repositoryId:\\\"R_1\\\",title:\\\"t\\\"," + "body:\\\""
                + token + "\\\"}){clientMutationId}}\"}";
        var result = inspector(blocking(List.of(), List.of()), secretScanning(true))
                .inspect(ProposalContent.fromGraphQlBody(MAPPER.readTree(body)), payload(body));
        assertTrue(
                ProposalContent.fromGraphQlBody(MAPPER.readTree(body)).isEmpty(),
                "no variables to attribute — coverage must come from the payload walk");
        assertFalse(result.isEmpty(), "a secret inlined in the query text must still be caught");
    }

    /**
     * The two-layer escaping a GraphQL request actually has. JSON and GraphQL share unicode-escape syntax, so a single
     * backslash in the query is consumed by the JSON transport; a GraphQL-only escape is a doubled backslash on the
     * wire, which survives JSON decoding intact and is resolved only by parsing the query.
     */
    @Test
    void catchesASecretEscapedInsideAGraphQlStringLiteral() {
        String token = realisticToken();
        // "ghp_..." as a GraphQL literal; Jackson doubles the backslash when it writes the JSON body.
        // Split so javac's unicode pre-processor does not eat the sequence in this source file.
        String escaped = "\\" + "u00" + Integer.toHexString(token.charAt(0)) + token.substring(1);
        String query = "mutation{createIssue(input:{body:\"" + escaped + "\"}){clientMutationId}}";
        String body = MAPPER.writeValueAsString(Map.of("query", query));

        var jsonOnly = payload(body);
        assertFalse(jsonOnly.combined().contains(token), "neither raw nor JSON-decoded reading reveals it");

        var withLiterals = ProposalPayload.of(
                body.getBytes(StandardCharsets.UTF_8),
                MAPPER.readTree(body),
                GraphQlLiterals.from(MAPPER.readTree(body).get("query").asString()));
        assertTrue(withLiterals.combined().contains(token), "the GraphQL parser unescapes the literal");

        var result =
                inspector(blocking(List.of(), List.of()), secretScanning(true)).inspect(List.of(), withLiterals);
        assertFalse(result.isEmpty(), "an escaped GraphQL literal must still be caught");
    }

    /** The bypass this closes: everything in the query string, nothing in the body. */
    @Test
    void catchesABlockedTermCarriedOnlyInTheQueryString() {
        var payload =
                ProposalPayload.of(new byte[0], null, "title=t&description=see%20internal.corp.example.com", List.of());
        var result = inspector(blocking(List.of("internal.corp.example.com"), List.of()), secretScanning(false))
                .inspect(List.of(), payload);
        assertEquals(1, result.size(), result::toString);
    }

    /** Synthetic but Luhn-valid, and paired with a context keyword the bundle requires. */
    private static final String SIN_IN_PROSE = "employee sin: 123 456 782";

    @Test
    void blocksAContentPatternMatchInProposalProse() {
        var payload = payload("{\"description\":\"" + SIN_IN_PROSE + "\"}");
        var result = inspector(blocking(List.of(), List.of()), secretScanning(false), bundles("national-id-ca"))
                .inspect(List.of(), payload);
        assertEquals(1, result.size(), result::toString);
        assertTrue(result.get(0).contains("Social Insurance Number"), result::toString);
    }

    /** The matched value is what the rule exists to withhold; it must not ride along into the audit record. */
    @Test
    void neverRepeatsTheMatchedValueInTheViolation() {
        var payload = payload("{\"description\":\"" + SIN_IN_PROSE + "\"}");
        var result = inspector(blocking(List.of(), List.of()), secretScanning(false), bundles("national-id-ca"))
                .inspect(List.of(), payload);
        assertFalse(result.get(0).contains("123 456 782"), result::toString);
    }

    @Test
    void scansProposalsOnlyWhenBundlesAreSelected() {
        var payload = payload("{\"description\":\"" + SIN_IN_PROSE + "\"}");
        assertTrue(
                inspector(blocking(List.of(), List.of()), secretScanning(false), ContentPatternConfig.defaultConfig())
                        .inspect(List.of(), payload)
                        .isEmpty(),
                "disabled by default, so an operator who never opted in is unaffected");

        var enabledNoBundles = ContentPatternConfig.builder().enabled(true).build();
        assertTrue(
                inspector(blocking(List.of(), List.of()), secretScanning(false), enabledNoBundles)
                        .inspect(List.of(), payload)
                        .isEmpty(),
                "enabled but nothing selected scans nothing");
    }

    @Test
    void honoursScanProposalsOptOut() {
        var optedOut = ContentPatternConfig.builder()
                .enabled(true)
                .bundles(List.of("national-id-ca"))
                .scanProposals(false)
                .build();
        var payload = payload("{\"description\":\"" + SIN_IN_PROSE + "\"}");
        assertTrue(inspector(blocking(List.of(), List.of()), secretScanning(false), optedOut)
                .inspect(List.of(), payload)
                .isEmpty());
    }

    /** Same coverage the other rules get: the query string is prose a proposal can be carried entirely in. */
    @Test
    void catchesAContentPatternCarriedOnlyInTheQueryString() {
        var payload =
                ProposalPayload.of(new byte[0], null, "description=employee%20sin%3A%20123%20456%20782", List.of());
        var result = inspector(blocking(List.of(), List.of()), secretScanning(false), bundles("national-id-ca"))
                .inspect(List.of(), payload);
        assertEquals(1, result.size(), result::toString);
    }
}
