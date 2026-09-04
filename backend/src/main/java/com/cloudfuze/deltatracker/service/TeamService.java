package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.config.CacheConfig;
import com.cloudfuze.deltatracker.dto.TeamDto;
import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.repository.TeamRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Owns teams and the manager -> engineers mapping the project dashboard's engineer picker needs.
 *
 * <pre>
 *   Team "Team 5"
 *     |
 *     +-- AppUser role=MIGRATION_MANAGER  abhishikth.yenugula   \  both managers of the SAME team,
 *     +-- AppUser role=MIGRATION_MANAGER  ajay.singh            /  so both see the same 5 engineers
 *     |
 *     +-- AppUser role=MIGRATION_ENGINEER neelima.krotta
 *     +-- AppUser role=MIGRATION_ENGINEER amulya.anapuram
 *     +-- ... etc
 *
 *   engineersByManager() flattens that into:
 *     "abhishikth.yenugula@..." -> [neelima..., amulya..., ...]
 *     "ajay.singh@..."          -> [neelima..., amulya..., ...]   (same list, by design)
 * </pre>
 *
 * <p>Depends on AppUserRepository rather than AppUserService on purpose: AppUserService needs
 * TeamRepository for its CSV import, so a service-to-service edge in both directions would be a
 * circular bean dependency.
 */
@Service
@Transactional
public class TeamService {

    private final TeamRepository teamRepository;
    private final AppUserRepository appUserRepository;
    private final RosterCache rosterCache;

    public TeamService(TeamRepository teamRepository, AppUserRepository appUserRepository,
                        RosterCache rosterCache) {
        this.teamRepository = teamRepository;
        this.appUserRepository = appUserRepository;
        this.rosterCache = rosterCache;
    }

    public List<TeamDto> list() {
        return teamRepository.findAllByOrderByNameAsc().stream()
                .map(team -> TeamDto.fromEntity(team, membersOf(team.getId()),
                        emailsOf(team.getId(), AppUserRole.MIGRATION_MANAGER),
                        emailsOf(team.getId(), AppUserRole.MIGRATION_ENGINEER)))
                .toList();
    }

    /**
     * manager email (lowercase) -> the engineer emails on that manager's team.
     *
     * <p>Cached alongside the per-role roster lists: read on every project dashboard load, written
     * only when an admin edits a team or a user's team. Any write on either side calls
     * {@link RosterCache#evict()}, so this is never served stale past that.
     *
     * <p>A manager on no team simply has no entry here -- callers treat "absent" as "fall back to
     * the full engineer list", which is what keeps the dropdown from ever coming up empty.
     */
    // Key spelled out rather than defaulted: a no-arg method keys on SimpleKey.EMPTY, which
    // collided with AppUserService.managerCandidateEmails on this same cache and served each
    // method the other's value. See that method's javadoc for the failure it caused.
    @Cacheable(value = CacheConfig.ROSTER_EMAILS_CACHE, key = "'engineersByManager'")
    public Map<String, List<String>> engineersByManager() {
        Map<String, List<String>> byManager = new LinkedHashMap<>();
        for (Team team : teamRepository.findAllByOrderByNameAsc()) {
            List<String> engineers = emailsOf(team.getId(), AppUserRole.MIGRATION_ENGINEER);
            for (String managerEmail : managerEmailsOf(team.getId())) {
                // MERGED, not put: a manager may now hold several teams, and their engineer pool is
                // the union across all of them. A plain put would leave whichever team sorted last
                // as the only one visible, silently hiding the rest of their people. Deduplicated
                // because an engineer can be on more than one of that manager's teams.
                byManager.merge(managerEmail.toLowerCase(), engineers, (existing, added) -> {
                    LinkedHashSet<String> union = new LinkedHashSet<>(existing);
                    union.addAll(added);
                    return List.copyOf(union);
                });
            }
        }
        return byManager;
    }

    public TeamDto create(String name, String actingAdminEmail) {
        String trimmed = requireName(name);
        if (teamRepository.existsByNameIgnoreCase(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A team named \"" + trimmed + "\" already exists.");
        }
        Team saved = teamRepository.save(new Team(trimmed, actingAdminEmail));
        rosterCache.evict();
        return TeamDto.fromEntity(saved, List.of(), List.of(), List.of());
    }

    public TeamDto rename(Long id, String name) {
        String trimmed = requireName(name);
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team " + id + " not found"));
        teamRepository.findByNameIgnoreCase(trimmed)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "A team named \"" + trimmed + "\" already exists.");
                });
        team.setName(trimmed);
        Team saved = teamRepository.save(team);
        rosterCache.evict();
        return TeamDto.fromEntity(saved, membersOf(id),
                emailsOf(id, AppUserRole.MIGRATION_MANAGER),
                emailsOf(id, AppUserRole.MIGRATION_ENGINEER));
    }

    /**
     * Deletes a team, detaching its members first.
     *
     * <p>Members lose only THIS membership rather than being deleted -- a team being dissolved must
     * never remove people from the access allowlist, and with multi-team membership it must not
     * remove them from their other teams either. Somebody left on no team falls back to the
     * unfiltered engineer list until an admin places them again.
     */
    public void delete(Long id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team " + id + " not found"));
        List<AppUser> members = appUserRepository.findByTeams_Id(id);
        for (AppUser member : members) {
            member.getTeams().removeIf(t -> t.getId().equals(id));
        }
        appUserRepository.saveAll(members);
        teamRepository.delete(team);
        rosterCache.evict();
    }

    /**
     * Sets a user's teams to exactly {@code teamIds} -- an empty or null list takes them off every
     * team.
     *
     * <p>Replace, not add, because the caller that matters is the admin's team checkboxes: they show
     * the full picture, so what comes back IS the full picture. A caller that means "also add them
     * to this team" sends the union it wants, which keeps one endpoint honest instead of two whose
     * difference is invisible in the payload.
     */
    public void assign(String email, List<Long> teamIds) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException(email + " isn't on the access list"));
        LinkedHashSet<Team> resolved = new LinkedHashSet<>();
        if (teamIds != null) {
            for (Long teamId : teamIds) {
                if (teamId == null) {
                    continue;
                }
                resolved.add(teamRepository.findById(teamId)
                        .orElseThrow(() -> new ResourceNotFoundException("Team " + teamId + " not found")));
            }
        }
        // Mutated in place rather than replaced wholesale: Hibernate tracks THIS collection instance
        // to work out which join rows to add and delete.
        user.getTeams().clear();
        user.getTeams().addAll(resolved);
        appUserRepository.save(user);
        rosterCache.evict();
    }

    /** Resolves a team by name for the CSV import path. Empty/blank means "no team". */
    public Team resolveByName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return teamRepository.findByNameIgnoreCase(name.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "\"" + name.trim() + "\" isn't a known team"));
    }

    /**
     * The engineer emails on {@code managerEmail}'s team, or empty if they're on no team / unknown.
     * Used to auto-populate a project's engineers from whoever manages it, instead of a manual pick.
     */
    public LinkedHashSet<String> engineersOf(String managerEmail) {
        if (managerEmail == null || managerEmail.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(engineersByManager().getOrDefault(managerEmail.toLowerCase(), List.of()));
    }

    /**
     * Whether {@code callerEmail} is CURRENTLY on {@code managerEmail}'s team -- the live check every
     * "is this engineer allowed to act on this project" test should use instead of trusting
     * {@code Project.engineerEmails}, which is only ever a snapshot copied in at project-creation or
     * manager-reassignment time (see {@code ProjectService.create}/{@code updateDetails}).
     *
     * <p>Moving an engineer to a different team (via {@link #assign}) does not touch any project's
     * stored snapshot, so a check against that snapshot would keep granting access to the OLD team's
     * projects forever and never grant it on the NEW team's -- this is what actually happened and is
     * the reason this method exists rather than every caller re-deriving the same live lookup.
     */
    public boolean isCurrentlyOnManagersTeam(String managerEmail, String callerEmail) {
        if (callerEmail == null || callerEmail.isBlank()) {
            return false;
        }
        return engineersOf(managerEmail).stream().anyMatch(callerEmail::equalsIgnoreCase);
    }

    /**
     * Who counts as a manager OF this team for engineer-scoping purposes: the MIGRATION_MANAGER
     * role, plus anyone an admin flagged assignable (typically an ADMIN who also runs engagements).
     *
     * <p>Without the second group a flagged admin had no entry in engineersByManager, so their
     * projects fell through to the "absent means show everyone" fallback and auto-assignment gave
     * them no engineers at all -- even with the admin sitting on the right team.
     */
    private List<String> managerEmailsOf(Long teamId) {
        LinkedHashSet<String> emails = new LinkedHashSet<>(emailsOf(teamId, AppUserRole.MIGRATION_MANAGER));
        for (AppUser member : appUserRepository.findByTeams_IdAndAssignableAsManagerTrue(teamId)) {
            emails.add(member.getEmail());
        }
        return List.copyOf(emails);
    }

    private List<String> emailsOf(Long teamId, AppUserRole role) {
        return appUserRepository.findByTeams_IdAndRole(teamId, role).stream()
                .map(AppUser::getEmail)
                .toList();
    }

    private List<String> membersOf(Long teamId) {
        List<String> emails = new ArrayList<>();
        for (AppUser member : appUserRepository.findByTeams_Id(teamId)) {
            emails.add(member.getEmail());
        }
        return emails;
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A team needs a name.");
        }
        return name.trim();
    }
}
