package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TeamService} -- the manager to engineers mapping that scopes the project
 * dashboard's engineer picker.
 *
 * <p>The cases that matter are the ones a single-manager-per-team model would get wrong: a team with
 * TWO managers (both must see the same engineers) and a manager on NO team (must be absent from the
 * map so the caller falls back to the unfiltered list rather than showing an empty dropdown).
 */
@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private TeamService service;

    private static Team team(long id, String name) {
        Team t = new Team(name, "admin@cloudfuze.com");
        t.setId(id);
        return t;
    }

    private static AppUser user(String email, AppUserRole role) {
        return new AppUser(email, role, "admin@cloudfuze.com");
    }

    @BeforeEach
    void setUp() {
        lenient().when(cacheManager.getCache(any())).thenReturn(cache);
        service = new TeamService(teamRepository, appUserRepository, new RosterCache(cacheManager));
    }

    @Test
    void engineersByManagerMapsEachManagerToTheirOwnTeamsEngineers() {
        when(teamRepository.findAllByOrderByNameAsc()).thenReturn(List.of(team(1L, "Team 1"), team(2L, "Team 2")));
        when(appUserRepository.findByTeamIdAndRole(1L, AppUserRole.MIGRATION_MANAGER))
                .thenReturn(List.of(user("harika.velidi@cloudfuze.com", AppUserRole.MIGRATION_MANAGER)));
        when(appUserRepository.findByTeamIdAndRole(1L, AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of(user("siva.kota@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER)));
        when(appUserRepository.findByTeamIdAndRole(2L, AppUserRole.MIGRATION_MANAGER))
                .thenReturn(List.of(user("raghu.yellani@cloudfuze.com", AppUserRole.MIGRATION_MANAGER)));
        when(appUserRepository.findByTeamIdAndRole(2L, AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of(user("ramana.reddy@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER)));

        Map<String, List<String>> byManager = service.engineersByManager();

        assertThat(byManager.get("harika.velidi@cloudfuze.com")).containsExactly("siva.kota@cloudfuze.com");
        assertThat(byManager.get("raghu.yellani@cloudfuze.com")).containsExactly("ramana.reddy@cloudfuze.com");
        // The point of the whole feature: Team 1's manager must NOT see Team 2's engineer.
        assertThat(byManager.get("harika.velidi@cloudfuze.com")).doesNotContain("ramana.reddy@cloudfuze.com");
    }

    @Test
    void bothManagersOfATeamSeeTheSameEngineers() {
        // Teams 5 and 6 in the real roster each have two managers. A single managerEmail column on
        // AppUser could not express this without splitting the team's engineers between them.
        when(teamRepository.findAllByOrderByNameAsc()).thenReturn(List.of(team(5L, "Team 5")));
        when(appUserRepository.findByTeamIdAndRole(5L, AppUserRole.MIGRATION_MANAGER)).thenReturn(List.of(
                user("abhishikth.yenugula@cloudfuze.com", AppUserRole.MIGRATION_MANAGER),
                user("ajay.singh@cloudfuze.com", AppUserRole.MIGRATION_MANAGER)));
        when(appUserRepository.findByTeamIdAndRole(5L, AppUserRole.MIGRATION_ENGINEER)).thenReturn(List.of(
                user("neelima.krotta@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER),
                user("amulya.anapuram@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER)));

        Map<String, List<String>> byManager = service.engineersByManager();

        assertThat(byManager).containsOnlyKeys("abhishikth.yenugula@cloudfuze.com", "ajay.singh@cloudfuze.com");
        assertThat(byManager.get("abhishikth.yenugula@cloudfuze.com"))
                .isEqualTo(byManager.get("ajay.singh@cloudfuze.com"))
                .containsExactly("neelima.krotta@cloudfuze.com", "amulya.anapuram@cloudfuze.com");
    }

    @Test
    void managerOnNoTeamIsAbsentSoTheCallerFallsBackToEveryEngineer() {
        // An ABSENT key (not an empty list) is what the frontend keys its fallback off. If this
        // returned an empty list instead, the dropdown would render empty and block assignment.
        when(teamRepository.findAllByOrderByNameAsc()).thenReturn(List.of(team(1L, "Team 1")));
        when(appUserRepository.findByTeamIdAndRole(1L, AppUserRole.MIGRATION_MANAGER)).thenReturn(List.of());
        when(appUserRepository.findByTeamIdAndRole(1L, AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of(user("siva.kota@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER)));

        assertThat(service.engineersByManager()).doesNotContainKey("someone.with.no.team@cloudfuze.com");
    }

    @Test
    void managerKeysAreLowercasedSoEmailCaseNeverBreaksTheLookup() {
        when(teamRepository.findAllByOrderByNameAsc()).thenReturn(List.of(team(3L, "Team 3")));
        when(appUserRepository.findByTeamIdAndRole(3L, AppUserRole.MIGRATION_MANAGER))
                .thenReturn(List.of(user("Sravan.Kesaram@cloudfuze.com", AppUserRole.MIGRATION_MANAGER)));
        when(appUserRepository.findByTeamIdAndRole(3L, AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of(user("swaroop@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER)));

        assertThat(service.engineersByManager()).containsKey("sravan.kesaram@cloudfuze.com");
    }

    @Test
    void duplicateTeamNameIsRejectedCaseInsensitively() {
        when(teamRepository.existsByNameIgnoreCase("team 1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("team 1", "admin@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deletingATeamDetachesItsMembersRatherThanDeletingThem() {
        AppUser member = user("siva.kota@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER);
        member.setTeam(team(1L, "Team 1"));
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team(1L, "Team 1")));
        when(appUserRepository.findByTeamId(1L)).thenReturn(List.of(member));

        service.delete(1L);

        // Losing a team must never remove somebody from the access allowlist.
        assertThat(member.getTeam()).isNull();
        verify(appUserRepository).saveAll(List.of(member));
        verify(teamRepository).delete(any(Team.class));
        verify(cache).clear();
    }

    @Test
    void unknownTeamNameOnImportIsRejectedRatherThanSilentlyIgnored() {
        when(teamRepository.findByNameIgnoreCase("Team 9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveByName("Team 9"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("known team");
    }

    @Test
    void blankTeamNameResolvesToNoTeam() {
        assertThat(service.resolveByName("  ")).isNull();
        assertThat(service.resolveByName(null)).isNull();
    }

    @Test
    void assigningANullTeamTakesThePersonOffEveryTeam() {
        AppUser member = user("siva.kota@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER);
        member.setTeam(team(1L, "Team 1"));
        when(appUserRepository.findByEmailIgnoreCase("siva.kota@cloudfuze.com")).thenReturn(Optional.of(member));

        service.assign("siva.kota@cloudfuze.com", null);

        assertThat(member.getTeam()).isNull();
        verify(appUserRepository).save(member);
        verify(cache).clear();
    }
}
