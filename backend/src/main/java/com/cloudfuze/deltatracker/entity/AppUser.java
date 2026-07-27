package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(columnNames = {"email"}))
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppUserRole role = AppUserRole.MIGRATION_ENGINEER;

    @Column(name = "added_by")
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt = LocalDateTime.now();

    public AppUser(String email, AppUserRole role, String addedBy) {
        this.email = email.toLowerCase();
        this.role = role;
        this.addedBy = addedBy;
        this.addedAt = LocalDateTime.now();
    }
}
