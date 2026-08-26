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

    // Drives the team-scoped engineer dropdown: every member of a team holding a given role.
    List<AppUser> findByTeamIdAndRole(Long teamId, AppUserRole role);

    List<AppUser> findByTeamId(Long teamId);

    // Used when deleting a team, to detach its members rather than orphan a dangling team_id.
    long countByTeamId(Long teamId);
}
