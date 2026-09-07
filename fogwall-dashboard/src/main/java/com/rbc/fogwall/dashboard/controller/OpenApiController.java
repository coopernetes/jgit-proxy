package com.rbc.fogwall.dashboard.controller;

import com.rbc.fogwall.build.BuildInfo;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Serves the OpenAPI 3 specification for the fogwall REST API. The spec is generated at startup by walking Spring's
 * {@link RequestMappingHandlerMapping}, so it always reflects the actual registered routes.
 *
 * <p>Request body and response schemas are resolved via {@link ModelConverters} (Jackson-backed) and collected into the
 * spec's {@code components/schemas} section so that Swagger UI can render them.
 *
 * <ul>
 *   <li>{@code GET /api/openapi.yaml} — YAML format (machine-readable)
 *   <li>{@code GET /api/openapi.json} — JSON format
 * </ul>
 *
 * Both endpoints are public (no authentication required). Swagger UI is served at {@code /swagger-ui/}.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OpenApiController {

    /** Controllers whose endpoints should not appear in the public spec. */
    private static final Set<String> EXCLUDED = Set.of("OpenApiController", "SpaController");

    private final RequestMappingHandlerMapping handlerMapping;

    private String specYaml;
    private String specJson;

    /**
     * Version and commit of the running build. {@code commit} is additive on this response and reads {@code unknown}
     * when the build could not establish one.
     */
    public record VersionResponse(String version, String commit, String apiDocs) {}

    @GetMapping
    public ResponseEntity<VersionResponse> getSpec() {
        BuildInfo build = BuildInfo.get();
        return ResponseEntity.ok(new VersionResponse(build.version(), build.commit(), "/api/openapi.json"));
    }

    @PostConstruct
    public void buildSpec() {
        Map<String, Schema> componentSchemas = new LinkedHashMap<>();

        OpenAPI spec = new OpenAPI()
                .info(
                        new Info()
                                .title("fogwall API")
                                .version(BuildInfo.get().version())
                                .description(
                                        "REST API for fogwall. Covers push approval workflow, user management, access control rules, and provider configuration."))
                .components(new Components()
                        .addSecuritySchemes(
                                "apiKey",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Api-Key")
                                        .description(
                                                "API key for programmatic access. Configured via the FOGWALL_API_KEY environment variable."))
                        .addSecuritySchemes(
                                "session",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("JSESSIONID")
                                        .description("Session cookie obtained via form login at /login.")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .addSecurityItem(new SecurityRequirement().addList("session"));

        // Group handler methods by path for stable, sorted output
        var byPath = new TreeMap<String, List<Map.Entry<RequestMappingInfo, HandlerMethod>>>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (EXCLUDED.contains(entry.getValue().getBeanType().getSimpleName())) continue;
            for (String path : resolvePatterns(entry.getKey())) {
                byPath.computeIfAbsent(path, p -> new ArrayList<>()).add(entry);
            }
        }

        Paths paths = new Paths();
        for (var entry : byPath.entrySet()) {
            PathItem pathItem = new PathItem();
            for (var mapping : entry.getValue()) {
                Operation op = buildOperation(mapping.getValue(), componentSchemas);
                for (RequestMethod method : effectiveMethods(mapping.getKey())) {
                    assignOperation(pathItem, method, op);
                }
            }
            paths.addPathItem(entry.getKey(), pathItem);
        }
        spec.setPaths(paths);

        if (!componentSchemas.isEmpty()) {
            componentSchemas.forEach((k, v) -> spec.getComponents().addSchemas(k, v));
        }

        this.specYaml = Yaml.pretty(spec);
        this.specJson = Json.pretty(spec);
    }

    private Set<String> resolvePatterns(RequestMappingInfo info) {
        var pc = info.getPathPatternsCondition();
        if (pc == null) return Set.of();
        var result = new LinkedHashSet<String>();
        pc.getPatterns().forEach(p -> result.add(p.getPatternString()));
        return result;
    }

    private Set<RequestMethod> effectiveMethods(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() ? Set.of(RequestMethod.GET) : methods;
    }

    private Operation buildOperation(HandlerMethod handler, Map<String, Schema> componentSchemas) {
        Operation op = new Operation();

        // Prefer @Operation annotation for operationId, summary, and description; fall back to method name.
        // Using FQN for the annotation class to avoid a name collision with io.swagger.v3.oas.models.Operation.
        var opAnnotation = handler.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        op.setOperationId(
                opAnnotation != null && !opAnnotation.operationId().isEmpty()
                        ? opAnnotation.operationId()
                        : handler.getMethod().getName());
        if (opAnnotation != null && !opAnnotation.summary().isEmpty()) {
            op.setSummary(opAnnotation.summary());
        }
        if (opAnnotation != null && !opAnnotation.description().isEmpty()) {
            op.setDescription(opAnnotation.description());
        }

        // Prefer @Tag on the controller class; fall back to trimmed class name.
        Tag tagAnnotation = handler.getBeanType().getAnnotation(Tag.class);
        op.addTagsItem(
                tagAnnotation != null
                        ? tagAnnotation.name()
                        : handler.getBeanType().getSimpleName().replace("Controller", ""));

        // Path and query parameters
        List<Parameter> params = new ArrayList<>();
        for (var mp : handler.getMethodParameters()) {
            PathVariable pv = mp.getParameterAnnotation(PathVariable.class);
            if (pv != null) {
                String name = pv.value().isEmpty() ? mp.getParameterName() : pv.value();
                params.add(new Parameter().in("path").name(name).required(true).schema(new StringSchema()));
            }
            RequestParam rp = mp.getParameterAnnotation(RequestParam.class);
            if (rp != null) {
                String name = rp.value().isEmpty() ? mp.getParameterName() : rp.value();
                // A defaultValue makes the param optional regardless of the required() flag.
                boolean hasDefault = !ValueConstants.DEFAULT_NONE.equals(rp.defaultValue());
                params.add(new Parameter()
                        .in("query")
                        .name(name)
                        .required(rp.required() && !hasDefault)
                        .schema(new StringSchema()));
            }
        }
        if (!params.isEmpty()) op.setParameters(params);

        // Request body — resolve schema from the @RequestBody parameter type
        for (var mp : handler.getMethodParameters()) {
            if (mp.getParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class) != null) {
                Type bodyType = mp.getGenericParameterType();
                Schema<?> schema = resolveSchemaRef(bodyType, componentSchemas);
                if (schema != null) {
                    op.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                            .required(true)
                            .content(new Content().addMediaType("application/json", new MediaType().schema(schema))));
                }
                break;
            }
        }

        // Response — unwrap ResponseEntity<T> and resolve the body schema
        Type rawReturn = handler.getMethod().getGenericReturnType();
        Type returnType = unwrapResponseEntity(rawReturn);
        ApiResponses responses = new ApiResponses();
        if (returnType instanceof Class<?> c && (c == Void.class || c == void.class)) {
            responses.addApiResponse("204", new ApiResponse().description("No content"));
        } else {
            Schema<?> responseSchema = resolveSchemaRef(returnType, componentSchemas);
            ApiResponse ok = new ApiResponse().description("Success");
            if (responseSchema != null) {
                ok.content(new Content().addMediaType("application/json", new MediaType().schema(responseSchema)));
            }
            responses.addApiResponse("200", ok);
        }
        op.setResponses(responses);

        return op;
    }

    /** Unwraps {@code ResponseEntity<T>} to {@code T}; returns the type unchanged for all other types. */
    private static Type unwrapResponseEntity(Type type) {
        if (type instanceof ParameterizedType pt
                && ResponseEntity.class.equals(pt.getRawType())
                && pt.getActualTypeArguments().length > 0) {
            return pt.getActualTypeArguments()[0];
        }
        return type;
    }

    /**
     * Resolves a Java type to an OpenAPI {@link Schema}, adding any referenced component schemas to
     * {@code componentSchemas}. Returns {@code null} when the type is a wildcard, {@code void}, or cannot be resolved.
     */
    @SuppressWarnings("rawtypes")
    private Schema<?> resolveSchemaRef(Type type, Map<String, Schema> componentSchemas) {
        if (type instanceof WildcardType) return null;
        if (type instanceof Class<?> c && (c == Void.class || c == void.class)) return null;
        if (type instanceof Class<?> c && c == Object.class) return new Schema<>().type("object");
        if (type instanceof ParameterizedType pt && Map.class.isAssignableFrom((Class<?>) pt.getRawType())) {
            return new Schema<>().type("object");
        }
        try {
            ResolvedSchema resolved =
                    ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
            if (resolved == null || resolved.schema == null) return null;
            if (resolved.referencedSchemas != null) componentSchemas.putAll(resolved.referencedSchemas);
            return resolved.schema;
        } catch (Exception e) {
            log.debug("Could not resolve schema for {}: {}", type, e.getMessage());
            return null;
        }
    }

    private static void assignOperation(PathItem item, RequestMethod method, Operation op) {
        switch (method) {
            case GET -> item.setGet(op);
            case POST -> item.setPost(op);
            case PUT -> item.setPut(op);
            case DELETE -> item.setDelete(op);
            case PATCH -> item.setPatch(op);
            case HEAD -> item.setHead(op);
            case OPTIONS -> item.setOptions(op);
            default -> {
                /* TRACE etc. — skip */
            }
        }
    }

    @RequestMapping(value = "/openapi.yaml", method = RequestMethod.GET, produces = "application/yaml")
    public String getYaml() {
        return specYaml;
    }

    @RequestMapping(
            value = "/openapi.json",
            method = RequestMethod.GET,
            produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public String getJson() {
        return specJson;
    }
}
