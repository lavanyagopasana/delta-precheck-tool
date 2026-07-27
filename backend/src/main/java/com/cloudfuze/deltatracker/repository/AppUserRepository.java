package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRole(AppUserRole role);

    List<AppUser> findByRole(AppUserRole role);

    List<AppUser> findAllByOrderByAddedAtAsc();
}
