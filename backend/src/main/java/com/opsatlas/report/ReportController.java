package com.opsatlas.report;

import com.opsatlas.security.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
    record Confirmation(@AssertTrue boolean confirmed) {}

    private final ReportService reports;
    private final IdentityService identities;

    public ReportController(ReportService reports, IdentityService identities) {
        this.reports = reports;
        this.identities = identities;
    }

    @GetMapping("/api/analysis/sessions")
    public List<ReportService.SessionView> sessions(@AuthenticationPrincipal Jwt jwt) {
        return reports.sessions(identities.actor(identities.userId(jwt)).id());
    }

    @GetMapping("/api/reports")
    public List<ReportService.ReportView> reports(@AuthenticationPrincipal Jwt jwt) {
        return reports.reports(identities.actor(identities.userId(jwt)).id());
    }

    @PostMapping("/api/reports/{draftId}/confirm")
    public ReportService.ReportView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String draftId,
            @Valid @RequestBody Confirmation confirmation) {
        return reports.confirm(identities.actor(identities.userId(jwt)).id(), draftId);
    }

    @GetMapping("/api/audit")
    public List<ReportMapper.Audit> audit(@AuthenticationPrincipal Jwt jwt) {
        return reports.auditRows(identities.actor(identities.userId(jwt)).id());
    }
}
