package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ProjectSummaryDto;
import com.cloudfuze.deltatracker.dto.ProjectUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectMetabaseDatabaseRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectService}'s manager -> engineer auto-assignment: a project's
 * engineers are never picked by hand, they're always whoever is on the (current) Migration
 * Manager's team, per TeamService. This is the behaviour that replaced the old manual "add
 * engineer" picker on the project details page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMetabaseDatabaseRepository projectMetabaseDatabaseRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private WorkspacePairRepository workspacePairRepository;
    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private SignOffRepository signOffRepository;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private ServerService serverService;
    @Mock private WorkspaceCombinationService workspaceCombinationService;
    @Mock private AppUserService appUserService;
    @Mock private ServerPurgeService serverPurgeService;
    @Mock private TeamService teamService;
    @Mock private DeltaCycleService deltaCycleService;
    @Mock private ChangeLogService changeLogService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, projectMetabaseDatabaseRepository, serverRepository,
                workspacePairRepository, workspaceCombinationRepository, signOffRepository,
                preCheckSubmissionRepository, ticketRepository, serverService, workspaceCombinationService,
                appUserService, serverPurgeService, teamService, deltaCycleService,
                changeLogService);
        when(projectRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        when(serverRepository.findAll()).thenReturn(List.of());
        when(deltaCycleService.findDeclinedAwaitingResubmission()).thenReturn(List.of());
    }

    private Project savedProject() {
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createDrawsEngineersFromTheManagersTeamRatherThanLeavingThemEmpty() {
        when(appUserService.roleOf("mgr@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_MANAGER));
        when(teamService.engineersOf("mgr@cloudfuze.com"))
                .thenReturn(new LinkedHashSet<>(List.of("eng1@cloudfuze.com", "eng2@cloudfuze.com")));

        ProjectSummaryDto result = service.create("Acme", "mgr@cloudfuze.com", null);

        assertThat(result.getEngineerEmails()).containsExactlyInAnyOrder("eng1@cloudfuze.com", "eng2@cloudfuze.com");
        assertThat(savedProject().getEngineerEmails())
                .containsExactlyInAnyOrder("eng1@cloudfuze.com", "eng2@cloudfuze.com");
    }

    @Test
    void anEngineerCreatingAProjectIsIncludedEvenWhenNotOnTheChosenManagersTeam() {
        when(appUserService.roleOf("eng@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));
        when(teamService.engineersOf("mgr@cloudfuze.com"))
                .thenReturn(new LinkedHashSet<>(List.of("teammate@cloudfuze.com")));

        service.create("Acme", "eng@cloudfuze.com", "mgr@cloudfuze.com");

        assertThat(savedProject().getEngineerEmails())
                .containsExactlyInAnyOrder("teammate@cloudfuze.com", "eng@cloudfuze.com");
    }

    @Test
    void aProjectWithNoResolvedManagerGetsNoEngineersEither() {
        when(appUserService.roleOf("admin@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.ADMIN));
        when(teamService.engineersOf(null)).thenReturn(new LinkedHashSet<>());

        service.create("Acme", "admin@cloudfuze.com", null);

        assertThat(savedProject().getEngineerEmails()).isEmpty();
    }

    @Test
    void reassigningTheManagerSwapsInTheNewManagersWholeTeam() {
        Project existing = new Project("Acme", "admin@cloudfuze.com", "old.mgr@cloudfuze.com",
                new LinkedHashSet<>(List.of("old.engineer@cloudfuze.com")));
        existing.setId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(teamService.engineersOf("new.mgr@cloudfuze.com"))
                .thenReturn(new LinkedHashSet<>(List.of("new.engineer@cloudfuze.com")));

        ProjectUpdateRequest request = new ProjectUpdateRequest();
        request.setName("Acme");
        request.setMigrationManagerName("new.mgr@cloudfuze.com");

        service.updateDetails(1L, request, "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(savedProject().getEngineerEmails()).containsExactly("new.engineer@cloudfuze.com");
    }

    @Test
    void leavingTheManagerUnchangedLeavesTheEngineerListUntouched() {
        Project existing = new Project("Acme", "admin@cloudfuze.com", "mgr@cloudfuze.com",
                new LinkedHashSet<>(List.of("kept.engineer@cloudfuze.com")));
        existing.setId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(existing));

        ProjectUpdateRequest request = new ProjectUpdateRequest();
        request.setName("Acme renamed");
        request.setMigrationManagerName("mgr@cloudfuze.com");

        service.updateDetails(1L, request, "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(savedProject().getEngineerEmails()).containsExactly("kept.engineer@cloudfuze.com");
    }

    // ---- Visibility follows LIVE team membership, not the stored engineerEmails snapshot ----------
    // Regression coverage for a real incident: an admin moved an engineer ("dan") from one
    // Migration Manager's team to another's. The Team page correctly showed the new team, but dan
    // still only saw the OLD manager's projects and never gained access to the NEW manager's --
    // because every visibility/permission check compared against Project.engineerEmails, a snapshot
    // frozen at whatever moment the project's manager was last set, never touched by TeamService.assign.

    @Test
    void anEngineerMovedToTheManagersTeamCanSeeTheProjectEvenThoughTheStoredSnapshotNeverIncludedThem() {
        // The project's persisted engineerEmails snapshot is stale/empty -- "dan" joined the
        // manager's team only AFTER this project was created, so the snapshot never had him. Only
        // TeamService (live) knows he's on the team now.
        Project project = new Project("Acme", "admin@cloudfuze.com", "mgr@cloudfuze.com", new LinkedHashSet<>());
        project.setId(1L);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(project));
        when(teamService.isCurrentlyOnManagersTeam("mgr@cloudfuze.com", "dan@fuzebot.io")).thenReturn(true);

        List<ProjectSummaryDto> visible = service.list("dan@fuzebot.io", AppUserRole.MIGRATION_ENGINEER);

        assertThat(visible).extracting(ProjectSummaryDto::getName).containsExactly("Acme");
    }

    @Test
    void anEngineerMovedAwayFromTheManagersTeamLosesAccessEvenThoughTheStoredSnapshotStillNamesThem() {
        // The exact inverse of the incident: the persisted snapshot still lists "dan" (from back when
        // he was on this team), but TeamService says he has since moved to a different team.
        Project project = new Project("Acme", "admin@cloudfuze.com", "mgr@cloudfuze.com",
                new LinkedHashSet<>(List.of("dan@fuzebot.io")));
        project.setId(1L);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(project));
        when(teamService.isCurrentlyOnManagersTeam("mgr@cloudfuze.com", "dan@fuzebot.io")).thenReturn(false);

        List<ProjectSummaryDto> visible = service.list("dan@fuzebot.io", AppUserRole.MIGRATION_ENGINEER);

        assertThat(visible).isEmpty();
    }

    @Test
    void theProjectsEngineerEmailsInTheResponseReflectLiveTeamMembershipNotTheStoredSnapshot() {
        // The DTO's engineerEmails (what the frontend's canManage check and any display reads) must
        // also be live -- otherwise the frontend keeps showing/hiding controls based on stale data
        // even after the backend's own authorization is correct.
        Project project = new Project("Acme", "admin@cloudfuze.com", "mgr@cloudfuze.com",
                new LinkedHashSet<>(List.of("old.stale.snapshot@cloudfuze.com")));
        project.setId(1L);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(project));
        when(teamService.engineersOf("mgr@cloudfuze.com"))
                .thenReturn(new LinkedHashSet<>(List.of("current.teammate@cloudfuze.com")));

        List<ProjectSummaryDto> all = service.list("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(all).singleElement().satisfies(d -> {
            assertThat(d.getEngineerEmails()).containsExactly("current.teammate@cloudfuze.com");
            assertThat(d.getEngineerEmails()).doesNotContain("old.stale.snapshot@cloudfuze.com");
        });
    }

    // --- query count on the list endpoint ---------------------------------------------------------

    /** A project with one server, wired so serverRepository.findAll() reports it. */
    private Project projectWithServer(Long projectId, Long serverId, List<Server> sink) {
        Project project = new Project();
        project.setId(projectId);
        project.setName("Project " + projectId);
        Server server = new Server();
        server.setId(serverId);
        server.setName("srv-" + serverId);
        server.setProject(project);
        sink.add(server);
        return project;
    }

    @Test
    void listLoadsItsRowsOnceForThePageRatherThanOncePerProject() {
        // buildSummary batched its five lookups, but per project -- so this endpoint issued
        // 5 x (number of projects) queries. Against the live roster of 79 that is ~395 round trips on
        // a page every user opens, and one the Dashboard calls as well. The count must not grow with
        // the number of projects.
        List<Server> servers = new java.util.ArrayList<>();
        List<Project> projects = List.of(
                projectWithServer(1L, 10L, servers),
                projectWithServer(2L, 20L, servers),
                projectWithServer(3L, 30L, servers));
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(projects);
        when(serverRepository.findAll()).thenReturn(servers);
        when(workspacePairRepository.findByServerIdIn(any())).thenReturn(List.of());
        when(ticketRepository.findAllByCombinationServerIdIn(any())).thenReturn(List.of());
        when(workspaceCombinationRepository.findByServerIdIn(any())).thenReturn(List.of());

        List<ProjectSummaryDto> result = service.list("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(result).hasSize(3);
        // Once each, for all three projects together -- not once per project.
        verify(workspacePairRepository, times(1)).findByServerIdIn(any());
        verify(ticketRepository, times(1)).findAllByCombinationServerIdIn(any());
        verify(workspaceCombinationRepository, times(1)).findByServerIdIn(any());
    }

    @Test
    void listAsksForEveryVisibleProjectsServersInOneGo() {
        // The single batch has to cover all of them: if it only carried the first project's servers,
        // the others would silently summarise as empty rather than being slow.
        List<Server> servers = new java.util.ArrayList<>();
        List<Project> projects = List.of(
                projectWithServer(1L, 10L, servers),
                projectWithServer(2L, 20L, servers));
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(projects);
        when(serverRepository.findAll()).thenReturn(servers);
        when(workspacePairRepository.findByServerIdIn(any())).thenReturn(List.of());
        when(ticketRepository.findAllByCombinationServerIdIn(any())).thenReturn(List.of());
        when(workspaceCombinationRepository.findByServerIdIn(any())).thenReturn(List.of());

        service.list("admin@cloudfuze.com", AppUserRole.ADMIN);

        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(workspaceCombinationRepository).findByServerIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void aProjectSummaryCountsOnlyItsOwnServers() {
        // The batch maps now hold every project's rows, so the per-project narrowing is what stops one
        // project's figures picking up another's. This is the regression the shared cache could cause.
        List<Server> servers = new java.util.ArrayList<>();
        Project first = projectWithServer(1L, 10L, servers);
        projectWithServer(2L, 20L, servers);
        // Give project 1 a second server so the two projects have different counts.
        Server extra = new Server();
        extra.setId(11L);
        extra.setName("srv-11");
        extra.setProject(first);
        servers.add(extra);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(first, servers.get(1).getProject()));
        when(serverRepository.findAll()).thenReturn(servers);
        when(workspacePairRepository.findByServerIdIn(any())).thenReturn(List.of());
        when(ticketRepository.findAllByCombinationServerIdIn(any())).thenReturn(List.of());
        when(workspaceCombinationRepository.findByServerIdIn(any())).thenReturn(List.of());

        List<ProjectSummaryDto> result = service.list("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(result).extracting(ProjectSummaryDto::getServerCount).containsExactly(2, 1);
    }

    /**
     * A decline deletes the live sign-off chain (DeltaCycleService.rollOver) and keeps the outcome only
     * in DeltaCycle history. buildSummary read the live rows alone, so a declined combination matched
     * the "no chain yet" skip and landed in NO bucket -- combinationsDeclined stayed 0 and the
     * dashboard's Approvals donut rendered "Declined (0)" while the Approvals page listed the decline.
     */
    @Test
    void aDeclinedCombinationIsCountedAsDeclinedEvenThoughTheRolloverDeletedItsChain() {
        List<Server> servers = new java.util.ArrayList<>();
        Project project = projectWithServer(1L, 10L, servers);
        WorkspaceCombination combination = combinationOn(servers.get(0), 100L);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(project));
        when(serverRepository.findAll()).thenReturn(servers);
        when(workspacePairRepository.findByServerIdIn(any())).thenReturn(List.of());
        when(ticketRepository.findAllByCombinationServerIdIn(any())).thenReturn(List.of());
        when(workspaceCombinationRepository.findByServerIdIn(any())).thenReturn(List.of(combination));
        // The decline's own footprint: no live sign-off rows at all, one frozen DECLINED cycle.
        when(signOffRepository.findByCombinationIdIn(any())).thenReturn(List.of());
        when(preCheckSubmissionRepository.findByCombinationIdIn(any())).thenReturn(List.of());
        when(deltaCycleService.findDeclinedAwaitingResubmission())
                .thenReturn(List.of(declinedCycle(combination)));

        ProjectSummaryDto summary = service.list("admin@cloudfuze.com", AppUserRole.ADMIN).get(0);

        assertThat(summary.getCombinationsDeclined()).isEqualTo(1);
        assertThat(summary.getCombinationsAwaitingApproval()).isZero();
        assertThat(summary.getCombinationsFullyApproved()).isZero();
    }

    @Test
    void aResubmittedCombinationCountsAsAwaitingApprovalRatherThanStillDeclined() {
        // Its latest cycle row is still the DECLINED one -- a new cycle is only written when the fresh
        // attempt resolves -- so the frozen history alone would keep reporting a decline forever. The
        // live chain is what says somebody already refilled the checklist and it is moving again.
        List<Server> servers = new java.util.ArrayList<>();
        Project project = projectWithServer(1L, 10L, servers);
        WorkspaceCombination combination = combinationOn(servers.get(0), 100L);
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(project));
        when(serverRepository.findAll()).thenReturn(servers);
        when(workspacePairRepository.findByServerIdIn(any())).thenReturn(List.of());
        when(ticketRepository.findAllByCombinationServerIdIn(any())).thenReturn(List.of());
        when(workspaceCombinationRepository.findByServerIdIn(any())).thenReturn(List.of(combination));
        when(signOffRepository.findByCombinationIdIn(any()))
                .thenReturn(List.of(pendingSignOff(combination, SignOffRole.MIGRATION_LEAD)));
        when(preCheckSubmissionRepository.findByCombinationIdIn(any())).thenReturn(List.of());
        when(deltaCycleService.findDeclinedAwaitingResubmission())
                .thenReturn(List.of(declinedCycle(combination)));

        ProjectSummaryDto summary = service.list("admin@cloudfuze.com", AppUserRole.ADMIN).get(0);

        assertThat(summary.getCombinationsDeclined()).isZero();
        assertThat(summary.getCombinationsAwaitingApproval()).isEqualTo(1);
    }

    private WorkspaceCombination combinationOn(Server server, Long combinationId) {
        WorkspaceCombination combination = new WorkspaceCombination();
        combination.setId(combinationId);
        combination.setName("Teams to Slack");
        combination.setServer(server);
        return combination;
    }

    private DeltaCycle declinedCycle(WorkspaceCombination combination) {
        DeltaCycle cycle = new DeltaCycle(combination, 1, DeltaType.PRE_DELTA);
        cycle.setStatus(DeltaCycleStatus.DECLINED);
        cycle.setDeclinedByRole(SignOffRole.DEV_LEAD);
        cycle.setDeclinedBy("dev@cloudfuze.com");
        cycle.setDeclineReason("Evidence missing on two items.");
        // Normally populated by the read-only combination_id column; set by hand here because that is
        // the field the summary matches on.
        cycle.setCombinationId(combination.getId());
        return cycle;
    }

    private SignOff pendingSignOff(WorkspaceCombination combination, SignOffRole role) {
        SignOff signOff = new SignOff();
        signOff.setCombination(combination);
        signOff.setRole(role);
        signOff.setStatus(SignOffStatus.PENDING);
        return signOff;
    }
}
