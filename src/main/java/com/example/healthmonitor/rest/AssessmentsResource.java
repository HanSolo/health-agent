package com.example.healthmonitor.rest;

import com.example.healthmonitor.agent.AssessmentStore;
import com.example.healthmonitor.model.AssessmentRecord;
import com.example.healthmonitor.remediation.RemediationExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

@Path("assessments")
@ApplicationScoped
public class AssessmentsResource {
    public record Message(String message) { }

    @Inject AssessmentStore     store;
    @Inject RemediationExecutor executor;


    /** Newest first. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<AssessmentRecord> list() {
        return store.list();
    }

    /**
     * Approves a pending proposal and carries it out. Waits up to a few seconds for the monitored
     * machine to answer, then returns the updated assessment.
     */
    @POST
    @Path("{id}/approve")
    @Produces(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("id") final long id) {
        return decide(id, true);
    }

    @POST
    @Path("{id}/reject")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") final long id) {
        return decide(id, false);
    }

    private Response decide(final long id, final boolean approve) {
        if (store.find(id).isEmpty()) { return Response.status(Response.Status.NOT_FOUND).entity(new Message("No such assessment.")).build(); }
        // Only one request can claim a pending proposal, so a double click runs the action once.
        Optional<AssessmentRecord> claimed = store.claimPending(id);
        if (claimed.isEmpty()) {
            return Response.status(Response.Status.CONFLICT).entity(new Message("This proposal is not waiting for a decision.")).build();
        }
        AssessmentRecord updated = approve ? executor.execute(claimed.get()) : executor.reject(claimed.get());
        return Response.ok(updated).build();
    }
}
