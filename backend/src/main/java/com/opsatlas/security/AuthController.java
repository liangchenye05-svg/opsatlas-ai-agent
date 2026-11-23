package com.opsatlas.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    record Credentials(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {}

    private final IdentityService identities;

    public AuthController(IdentityService identities) {
        this.identities = identities;
    }

    @PostMapping("/api/auth/login")
    public IdentityService.Login login(@Valid @RequestBody Credentials credentials) {
        return identities.login(credentials.username(), credentials.password());
    }

    @GetMapping("/api/me")
    public IdentityService.Actor me(@AuthenticationPrincipal Jwt jwt) {
        return identities.actor(identities.userId(jwt));
    }

    @GetMapping("/api/stores")
    public List<IdentityMapper.Store> stores(@AuthenticationPrincipal Jwt jwt) {
        return identities.stores(identities.userId(jwt));
    }
}
