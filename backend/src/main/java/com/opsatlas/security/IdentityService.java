package com.opsatlas.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
    public record Actor(long id, String username, String displayName, IdentityMapper.Role role) {}
    public record Login(String accessToken, Actor user) {}

    private final IdentityMapper mapper;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;

    public IdentityService(IdentityMapper mapper, PasswordEncoder passwords, JwtEncoder encoder) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.encoder = encoder;
    }

    public Login login(String username, String password) {
        var account = mapper.byName(username);
        if (account == null || !passwords.matches(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("opsatlas").subject(Long.toString(account.id()))
                .issuedAt(now).expiresAt(now.plus(2, ChronoUnit.HOURS)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Login(token, actor(account));
    }

    public long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    public Actor actor(long userId) {
        return actor(active(userId));
    }

    public List<IdentityMapper.Store> stores(long userId) {
        return mapper.stores(active(userId));
    }

    public List<Long> storeIds(long userId, Long requestedStore, Long regionId) {
        var result = stores(userId).stream()
                .filter(store -> (requestedStore == null || requestedStore == store.id())
                        && (regionId == null || regionId == store.regionId()))
                .map(IdentityMapper.Store::id).toList();
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权访问所选门店或区域");
        }
        return result;
    }

    private IdentityMapper.Account active(long userId) {
        var account = mapper.byId(userId);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用");
        }
        return account;
    }

    private Actor actor(IdentityMapper.Account account) {
        return new Actor(account.id(), account.username(), account.displayName(), account.roleCode());
    }
}
