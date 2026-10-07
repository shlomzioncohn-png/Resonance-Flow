package com.rnd.gateway.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps Keycloak realm roles (the "lens" realm's {@code manager}/{@code employee}
 * roles — see teams/lens/keycloak/realm-lens.json) into Spring Security
 * authorities, so {@code .hasRole("MANAGER")} works against a real JWT.
 *
 * Spring's default {@code JwtGrantedAuthoritiesConverter} only reads a flat
 * "scope"/"scp" claim; Keycloak nests realm roles under
 * {@code realm_access.roles} instead, which needs its own converter.
 *
 * "manager" -&gt; ROLE_MANAGER, "employee" -&gt; ROLE_EMPLOYEE (Spring's hasRole()
 * always expects the ROLE_ prefix and compares case-sensitively on the rest,
 * so this upper-cases the role name to match).
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }

        List<String> authorityNames = roles.stream()
                .map(Object::toString)
                .map(role -> "ROLE_" + role.toUpperCase())
                .toList();

        return AuthorityUtils.createAuthorityList(authorityNames.toArray(new String[0]));
    }
}
