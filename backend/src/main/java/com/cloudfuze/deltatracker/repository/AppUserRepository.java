package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRole(AppUserRole role);

    List<AppUser> findByRole(AppUserRole role);

    // teams is LAZY, and AppUserService.list maps it for every row -- without this graph that is
    // one extra query per person (79 on the live roster) on a page an admin opens routinely.
    @EntityGraph(attributePaths = "teams")
    List<AppUser> findAllByOrderByAddedAtAsc();

    // Drives the team-scoped engineer dropdown: every member of a team holding a given role.
    // "Teams_Id" traverses the app_user_teams join table. The pre-multi-team equivalents read
    // app_users.team_id, which nothing but TeamMembershipBackfill may touch any more.
    List<AppUser> findByTeams_IdAndRole(Long teamId, AppUserRole role);

    List<AppUser> findByTeams_Id(Long teamId);


    // The people an admin has explicitly made assignable as a Migration Manager, regardless of role.
    List<AppUser> findByAssignableAsManagerTrue();

    // Same, narrowed to one team -- TeamService needs it to scope that person's engineer picker the
    // way it already scopes a real Migration Manager's.
    List<AppUser> findByTeams_IdAndAssignableAsManagerTrue(Long teamId);
}
