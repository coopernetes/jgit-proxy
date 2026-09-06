package com.rbc.fogwall.scmapi;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.parser.Parser;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a GraphQL request to the single operation the upstream will execute, and the single top-level field that
 * operation selects.
 *
 * <p>Walks the parsed AST rather than matching text: a client-supplied alias or a string literal containing a mutation
 * name carries the same substring while the operation performed differs.
 *
 * <p>Anything that cannot be reduced to one field is refused rather than partially checked. A document may contain more
 * than one operation, and a mutation's selection set may hold more than one root field — GraphQL executes every root
 * mutation field, serially — so authorizing the first and forwarding the rest would let an allowlisted mutation carry
 * an arbitrary second one. A root fragment is refused too: its contents are a level of indirection this does not
 * follow, so a mutation hidden behind one would look like a document with no mutation at all.
 *
 * <p>Verified {@code gh} traffic sends exactly one operation selecting exactly one field, with no root fragments.
 */
public final class GraphQlMutationParser {

    private GraphQlMutationParser() {}

    /**
     * The mutation field the request will execute, or empty when the selected operation is a read.
     *
     * @param operationName the request's {@code operationName}, which selects among multiple operations. Not a security
     *     input on its own — it picks which operation is authorized, and that same operation is what the upstream runs.
     * @throws GraphQlParseException if the document is malformed, names no executable operation, or resolves to
     *     anything other than one operation selecting one plain field
     */
    public static Optional<Field> selectMutationField(String query, String operationName) {
        Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException e) {
            // graphql-java throws several distinct unchecked exception types for malformed input
            // (InvalidSyntaxException and others) — caught broadly since any of them means the same
            // thing here: fail closed rather than let an unparseable document through.
            throw new GraphQlParseException("Malformed GraphQL document", e);
        }

        OperationDefinition operation = selectOperation(document, operationName);
        return switch (operation.getOperation()) {
            case QUERY -> Optional.empty();
            case MUTATION -> {
                requireNoUnusedVariables(operation);
                yield Optional.of(soleRootField(operation));
            }
            default ->
                throw new GraphQlParseException("Unsupported GraphQL operation type: " + operation.getOperation());
        };
    }

    /**
     * The one operation the upstream will run.
     *
     * <p>A document may legally carry several, with {@code operationName} choosing between them — but no supported CLI
     * sends more than one, and a second operation is somewhere to put a mutation that the authorized one distracts
     * from. Requiring a single operation removes the question of which one was checked.
     */
    private static OperationDefinition selectOperation(Document document, String operationName) {
        List<OperationDefinition> operations = new ArrayList<>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition operation) {
                operations.add(operation);
            }
        }
        if (operations.size() != 1) {
            throw new GraphQlParseException(
                    "A GraphQL request must carry exactly one operation; found " + operations.size());
        }
        OperationDefinition operation = operations.get(0);
        if (operationName != null && !operationName.isBlank() && !operationName.equals(operation.getName())) {
            throw new GraphQlParseException("operationName '" + operationName + "' names no operation in the document");
        }
        return operation;
    }

    /**
     * Every variable the request declares has to be one the operation actually uses.
     *
     * <p>An undeclared or unreferenced variable is a value the upstream ignores, which makes it a place to put a
     * second, plausible-looking target: the audit record would store it, and a reader of that record would take it for
     * what the request did. Requiring the two sets to match leaves the variables carrying exactly what was executed.
     */
    private static void requireNoUnusedVariables(OperationDefinition operation) {
        Set<String> declared = new LinkedHashSet<>();
        for (VariableDefinition definition : operation.getVariableDefinitions()) {
            declared.add(definition.getName());
        }
        Set<String> referenced = new LinkedHashSet<>();
        new NodeTraverser()
                .preOrder(
                        new NodeVisitorStub() {
                            @Override
                            public TraversalControl visitVariableReference(
                                    VariableReference node, TraverserContext<graphql.language.Node> context) {
                                referenced.add(node.getName());
                                return TraversalControl.CONTINUE;
                            }
                        },
                        List.of(operation));
        if (!declared.equals(referenced)) {
            throw new GraphQlParseException(
                    "A mutation's declared variables must be exactly those it references; declared " + declared
                            + ", referenced " + referenced);
        }
    }

    /** The mutation's only root field. Anything else — several fields, or a fragment — is refused. */
    private static Field soleRootField(OperationDefinition operation) {
        List<Selection> selections = operation.getSelectionSet() == null
                ? List.of()
                : List.copyOf(operation.getSelectionSet().getSelections());
        if (selections.size() != 1) {
            throw new GraphQlParseException("A mutation must select exactly one field; found " + selections.size());
        }
        if (!(selections.get(0) instanceof Field field)) {
            throw new GraphQlParseException("A mutation's root selection must be a field, not a fragment");
        }
        // Field#getName() is the schema field being selected (e.g. "createIssue"); a client-supplied
        // alias lives in Field#getAlias() and must never be used for the allowlist decision.
        return field;
    }
}
