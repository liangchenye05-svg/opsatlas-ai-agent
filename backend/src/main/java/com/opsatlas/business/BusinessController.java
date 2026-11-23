package com.opsatlas.business;

import com.opsatlas.security.IdentityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {
    private final BusinessService business;
    private final IdentityService identities;

    public BusinessController(BusinessService business, IdentityService identities) {
        this.business = business;
        this.identities = identities;
    }

    @GetMapping("/api/dashboard")
    public BusinessService.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return business.dashboard(identities.userId(jwt), storeId, regionId, from, to);
    }

    @GetMapping("/api/orders")
    public List<BusinessMapper.OrderRow> orders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) Long regionId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        return business.orders(identities.userId(jwt), storeId, regionId, from, to, page);
    }

    @GetMapping("/api/refunds")
    public List<BusinessMapper.RefundRow> refunds(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) Long regionId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        return business.refunds(identities.userId(jwt), storeId, regionId, from, to, page);
    }
}
