package com.eakp.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column  // nullable for OAuth-only users
    private String password;

    @Column(name = "full_name")
    private String fullName;

    @Column(nullable = false)
    private String role;  // USER | ADMIN

    @Column(name = "auth_provider", nullable = false)
    @Builder.Default
    private String authProvider = "LOCAL";  // LOCAL | GOOGLE | GITHUB

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ── UserDetails contract ─────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(com.eakp.common.model.EakpRole.from(role).authority()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired()    { return true; }
    @Override
    public boolean isAccountNonLocked()     { return true; }
    @Override
    public boolean isCredentialsNonExpired(){ return true; }
    @Override
    public boolean isEnabled()              { return active; }
}
