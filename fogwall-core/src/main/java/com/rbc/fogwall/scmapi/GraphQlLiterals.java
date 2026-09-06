package com.rbc.fogwall.scmapi;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Document;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.ObjectField;
import graphql.language.StringValue;
import graphql.parser.Parser;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects every literal value and name from a GraphQL document — the AST counterpart of walking a JSON body.
 *
 * <p>Needed because a GraphQL request carries <b>two</b> layers of escaping: the JSON transport that wraps the query,
 * and GraphQL's own string literals inside it. Decoding the JSON leaves the inner layer intact, so a token written as
 * {@code "\\u0067hp_…"} inside an inlined argument survives both the raw and JSON-decoded readings. The parser resolves
 * it: {@link StringValue#getValue()} returns the literal already unescaped.
 *
 * <p>It also covers arguments inlined in the query text rather than passed as variables, which the variables-based
 * field extraction cannot see at all.
 */
@Slf4j
public final class GraphQlLiterals {

    private GraphQlLiterals() {}

    /** Every literal and name in {@code query}, or empty when it will not parse. */
    public static List<String> from(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Document document;
        try {
            document = Parser.parse(query);
        } catch (Exception e) {
            // The gate filter parses this same query and refuses anything malformed, so reaching here means the
            // document parsed once already. Contribute nothing rather than fail the request twice over.
            log.debug("Could not parse GraphQL query for literal extraction: {}", e.getMessage());
            return List.of();
        }
        var values = new ArrayList<String>();
        new NodeTraverser().preOrder(new LiteralCollector(values), document);
        return values;
    }

    /** Visits every node type that can carry an operator-visible value or name. */
    private static final class LiteralCollector extends NodeVisitorStub {

        private final List<String> values;

        LiteralCollector(List<String> values) {
            this.values = values;
        }

        @Override
        public TraversalControl visitStringValue(StringValue node, TraverserContext<Node> context) {
            values.add(node.getValue());
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitIntValue(IntValue node, TraverserContext<Node> context) {
            values.add(node.getValue().toString());
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitFloatValue(FloatValue node, TraverserContext<Node> context) {
            values.add(node.getValue().toString());
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitBooleanValue(BooleanValue node, TraverserContext<Node> context) {
            values.add(Boolean.toString(node.isValue()));
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitEnumValue(EnumValue node, TraverserContext<Node> context) {
            values.add(node.getName());
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitArgument(Argument node, TraverserContext<Node> context) {
            values.add(node.getName());
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitObjectField(ObjectField node, TraverserContext<Node> context) {
            values.add(node.getName());
            return TraversalControl.CONTINUE;
        }
    }
}
