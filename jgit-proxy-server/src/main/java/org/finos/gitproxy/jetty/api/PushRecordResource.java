package org.finos.gitproxy.jetty.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;

/**
 * REST resource exposing push audit records for the dashboard. Mounted at {@code /api/pushes}.
 *
 * <p>All parameters are optional — omitting them returns all records up to {@code limit}.
 */
@Path("/pushes")
@Produces(MediaType.APPLICATION_JSON)
public class PushRecordResource {

    @Inject
    private PushStore pushStore;

    /**
     * List push records with optional filters.
     *
     * <p>Query params: {@code status} (RECEIVED|APPROVED|BLOCKED|FORWARDED|ERROR|REJECTED|CANCELED), {@code project},
     * {@code repo}, {@code user}, {@code limit} (default 50).
     */
    @GET
    public List<PushRecord> list(
            @QueryParam("status") String status,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo,
            @QueryParam("user") String user,
            @DefaultValue("50") @QueryParam("limit") int limit) {

        PushQuery.PushQueryBuilder query = PushQuery.builder().limit(limit).newestFirst(true);

        if (status != null && !status.isBlank()) {
            try {
                query.status(PushStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                        "Unknown status: " + status + ". Valid values: " + List.of(PushStatus.values()));
            }
        }
        if (project != null && !project.isBlank()) query.project(project);
        if (repo != null && !repo.isBlank()) query.repoName(repo);
        if (user != null && !user.isBlank()) query.user(user);

        return pushStore.find(query.build());
    }

    /** Get a single push record by ID. Returns 404 if not found. */
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") String id) {
        return pushStore
                .findById(id)
                .map(record -> Response.ok(record).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Push record not found: " + id + "\"}")
                        .build());
    }
}
