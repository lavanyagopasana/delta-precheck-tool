package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Seeds two demo/testing projects (Demo prjct, Mercado) with their servers, one combination each,
 * migration pairs, Mercado's two sample tickets, and Box to OneDrive's Delta History -- the same
 * data that got manually reconstructed once already after a local database reset wiped every
 * project/server/pair/ticket/sign-off row (app_users and teams survived because
 * AdminBootstrap/TeamRosterBootstrap already re-seed those; nothing did the same for this hand-built
 * sample data, so it was gone for good until reconstructed from memory of earlier screenshots).
 *
 * <p>Matches on project/server/combination name (and cycle count for Delta History) and never
 * overwrites or duplicates -- same convergent philosophy as {@link TeamRosterBootstrap}: safe to run
 * on every boot, a no-op once the data exists, and never touches a project/server/combination that
 * already has real work on it beyond what's seeded here.
 *
 * <p><b>Slack to Microsoft Teams' own sign-off/Delta history is deliberately NOT reconstructed</b> --
 * unlike Box to OneDrive, there's no reliable record of who actually approved it as Dev Lead/QA Lead,
 * and guessing a name would fabricate an identity rather than recover one. That combination seeds at
 * its natural fresh state (cycle 1, PENDING); its pre-check/approvals have to happen for real.
 *
 * <p>Also still NOT seeded for either combination: live pre-check items/submissions/sign-offs (the
 * in-flight, not-yet-resolved state) -- only Box to OneDrive's already-resolved cycle HISTORY is
 * reconstructed, since that's the part with a solid, specific, remembered record (who declined,
 * why, and who signed off the completed cycle) rather than in-progress state that would need to be
 * guessed at the checklist-item level.
 *
 * <p>Disable with {@code app.seed-sample-projects=false} (or {@code APP_SEED_SAMPLE_PROJECTS=false})
 * for an environment that doesn't want demo data -- a real deployment should turn this off.
 */
@Component
@Order(30)
public class SampleProjectBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleProjectBootstrap.class);

    private final ProjectRepository projectRepository;
    private final ServerRepository serverRepository;
    private final WorkspaceCombinationRepository combinationRepository;
    private final WorkspacePairRepository pairRepository;
    private final TicketRepository ticketRepository;
    private final DeltaCycleRepository deltaCycleRepository;
    private final DeltaCycleSignOffRepository deltaCycleSignOffRepository;

    // Defaults to FALSE. This writes invented projects, tickets and sign-off history into whatever
    // database it is pointed at, and it defaulted to true with no way to turn it off from the
    // environment -- the property was in no properties file, no compose file and no workflow, so
    // APP_SEED_SAMPLE_PROJECTS=false could not reach the container and demo rows arrived on every
    // production deploy. Fake data appearing unasked is the worse failure, so it is now opt-in:
    // set app.seed-sample-projects=true for a demo or a fresh local database.
    @Value("${app.seed-sample-projects:false}")
    private boolean enabled;

    public SampleProjectBootstrap(ProjectRepository projectRepository, ServerRepository serverRepository,
                                   WorkspaceCombinationRepository combinationRepository,
                                   WorkspacePairRepository pairRepository, TicketRepository ticketRepository,
                                   DeltaCycleRepository deltaCycleRepository,
                                   DeltaCycleSignOffRepository deltaCycleSignOffRepository) {
        this.projectRepository = projectRepository;
        this.serverRepository = serverRepository;
        this.combinationRepository = combinationRepository;
        this.pairRepository = pairRepository;
        this.ticketRepository = ticketRepository;
        this.deltaCycleRepository = deltaCycleRepository;
        this.deltaCycleSignOffRepository = deltaCycleSignOffRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            log.info("Sample project seeding is disabled (app.seed-sample-projects=false).");
            return;
        }
        seedDemoProject();
        seedMercadoProject();
    }

    private void seedDemoProject() {
        Project project = getOrCreateProject("Demo prjct", "Pravallika.Punumalli",
                "sasya.chella@cloudfuze.com", Set.of("pravallika.punumalli@cloudfuze.com"));
        Server server = getOrCreateServer(project, "https://demoprjct-server1", ProductType.MESSAGE);
        WorkspaceCombination combination = getOrCreateCombination(server, "Slack to Microsoft Teams");

        // Message product type -- self-mapped source/destination email, no path.
        List<String> users = List.of(
                "user01@qa-test-server-02.cloudfuze.com", "user02@qa-test-server-02.cloudfuze.com",
                "user03@qa-test-server-02.cloudfuze.com", "user04@qa-test-server-02.cloudfuze.com",
                "user05@qa-test-server-02.cloudfuze.com", "user06@qa-test-server-02.cloudfuze.com",
                "user07@qa-test-server-02.cloudfuze.com", "user08@qa-test-server-02.cloudfuze.com",
                "user09@qa-test-server-02.cloudfuze.com", "user10@qa-test-server-02.cloudfuze.com");
        seedPairsIfAbsent(server, combination.getName(), users.stream()
                .map(email -> {
                    WorkspacePair pair = new WorkspacePair(server, email, email);
                    pair.setCombination(combination.getName());
                    return pair;
                })
                .toList());
    }

    private void seedMercadoProject() {
        Project project = getOrCreateProject("Mercado", "dan",
                "sasya.chella@cloudfuze.com", Set.of("pravallika.punumalli@cloudfuze.com"));
        Server contentServer = getOrCreateServer(project, "https://mercado-server1", ProductType.CONTENT);
        getOrCreateServer(project, "https://mercado-server2", ProductType.EMAIL);
        getOrCreateServer(project, "https://mercado-server3", ProductType.MESSAGE);
        WorkspaceCombination combination = getOrCreateCombination(contentServer, "Box to OneDrive");

        // Content product type -- source/destination email plus path.
        String[][] rows = {
                {"user01", "/All Files/Projects/Q1 Reports", "/Documents/Projects/Q1 Reports"},
                {"user02", "/All Files/HR/Policies", "/Documents/HR/Policies"},
                {"user03", "/All Files/Finance/Invoices/2025", "/Documents/Finance/Invoices/2025"},
                {"user04", "/All Files/Marketing/Campaigns", "/Documents/Marketing/Campaigns"},
                {"user05", "/All Files/Engineering/Design Docs", "/Documents/Engineering/Design Docs"},
                {"user06", "/All Files/Sales/Contracts", "/Documents/Sales/Contracts"},
                {"user07", "/All Files/Shared/Team Photos", "/Documents/Shared/Team Photos"},
                {"user08", "/All Files/Legal/Compliance", "/Documents/Legal/Compliance"},
                {"user09", "/All Files/IT/Scripts", "/Documents/IT/Scripts"},
                {"user10", "/All Files/Support/Tickets Archive", "/Documents/Support/Tickets Archive"},
        };
        seedPairsIfAbsent(contentServer, combination.getName(), List.of(rows).stream()
                .map(row -> {
                    String email = row[0] + "@qa-test-server-02.cloudfuze.com";
                    WorkspacePair pair = new WorkspacePair(contentServer, email, email);
                    pair.setSourcePath(row[1]);
                    pair.setDestinationPath(row[2]);
                    pair.setCombination(combination.getName());
                    return pair;
                })
                .toList());

        seedTicketIfAbsent(combination, "https://neutaraticketing.cftools.live/issues/QA-1600",
                "Pravallika.Punumalli@cloudfuze.com", "QA-1600", "Vignesh T",
                "Document Egnyte to SharePoint Migration In-Scope, Out-of-Scope...",
                LocalDateTime.of(2026, 8, 6, 18, 11, 32));
        seedTicketIfAbsent(combination, "https://neutaraticketing.cftools.live/issues/QA-1571",
                "Pravallika.Punumalli@cloudfuze.com", "QA-1571", "amulya anapuram",
                "CrossFrame | Server Sanity",
                LocalDateTime.of(2026, 8, 3, 10, 32, 52));

        seedBoxToOneDriveDeltaHistoryIfAbsent(combination);
    }

    // Pre-Delta 1 and 2 were both declined by the Migration Manager before Dev Lead/QA Lead ever got
    // a turn (sequential chain), so neither of those two roles gets a row -- DeltaHistoryPanel
    // already renders a missing role as "--", which is the accurate state here, not a gap to fill.
    // Pre-Delta 3 completed with QA Lead skipped (Dev Lead decided it wasn't required).
    private void seedBoxToOneDriveDeltaHistoryIfAbsent(WorkspaceCombination combination) {
        if (deltaCycleRepository.countByCombinationId(combination.getId()) > 0) {
            return;
        }

        DeltaCycle cycle1 = deltaCycleRepository.save(declinedCycle(combination, 1, "ben",
                "Sasya.chella", "drive changes need to be updated"));
        deltaCycleSignOffRepository.save(declinedSignOff(cycle1, "Sasya.chella", "drive changes need to be updated"));

        DeltaCycle cycle2 = deltaCycleRepository.save(declinedCycle(combination, 2, "Pravallika.Punumalli",
                "Sasya.chella", "one time should be done"));
        deltaCycleSignOffRepository.save(declinedSignOff(cycle2, "Sasya.chella", "one time should be done"));

        LocalDateTime ran = LocalDateTime.of(2026, 8, 11, 12, 0);
        DeltaCycle cycle3 = new DeltaCycle(combination, 3, DeltaType.PRE_DELTA);
        cycle3.setStatus(DeltaCycleStatus.COMPLETED);
        cycle3.setSubmittedBy("Pravallika.Punumalli");
        cycle3.setDeltaStartedAt(ran);
        cycle3.setDeltaStartedBy("Pravallika.Punumalli");
        cycle3.setDeltaFinishedAt(ran);
        cycle3.setDeltaFinishedBy("Pravallika.Punumalli");
        cycle3 = deltaCycleRepository.save(cycle3);

        DeltaCycleSignOff manager = new DeltaCycleSignOff();
        manager.setCycle(cycle3);
        manager.setRole(SignOffRole.MIGRATION_LEAD);
        manager.setStatus(SignOffStatus.APPROVED);
        manager.setApprovedBy("Sasya.chella");
        manager.setApprovedAt(ran);
        deltaCycleSignOffRepository.save(manager);

        DeltaCycleSignOff devLead = new DeltaCycleSignOff();
        devLead.setCycle(cycle3);
        devLead.setRole(SignOffRole.DEV_LEAD);
        devLead.setStatus(SignOffStatus.APPROVED);
        devLead.setApprovedBy("Sruthi.Chimata");
        devLead.setApprovedAt(ran);
        devLead.setQaRequired(false);
        deltaCycleSignOffRepository.save(devLead);

        DeltaCycleSignOff qaLead = new DeltaCycleSignOff();
        qaLead.setCycle(cycle3);
        qaLead.setRole(SignOffRole.QA_LEAD);
        qaLead.setStatus(SignOffStatus.SKIPPED);
        deltaCycleSignOffRepository.save(qaLead);

        // Three cycles have already resolved -- the live combination is on its 4th, matching what
        // was actually observed (already seeded fresh/PENDING by getOrCreateCombination above; this
        // only advances the cycle number so it reads as "Delta 4", not "Delta 1", on the live form).
        combination.setCurrentCycleNumber(4);
        combinationRepository.save(combination);

        log.info("Seeded Delta History (3 cycles) for {} / {}.", combination.getServer().getName(), combination.getName());
    }

    private DeltaCycle declinedCycle(WorkspaceCombination combination, int cycleNumber, String submittedBy,
                                      String declinedBy, String reason) {
        DeltaCycle cycle = new DeltaCycle(combination, cycleNumber, DeltaType.PRE_DELTA);
        cycle.setStatus(DeltaCycleStatus.DECLINED);
        cycle.setSubmittedBy(submittedBy);
        cycle.setDeclinedByRole(SignOffRole.MIGRATION_LEAD);
        cycle.setDeclinedBy(declinedBy);
        cycle.setDeclineReason(reason);
        return cycle;
    }

    private DeltaCycleSignOff declinedSignOff(DeltaCycle cycle, String declinedBy, String reason) {
        DeltaCycleSignOff signOff = new DeltaCycleSignOff();
        signOff.setCycle(cycle);
        signOff.setRole(SignOffRole.MIGRATION_LEAD);
        signOff.setStatus(SignOffStatus.DECLINED);
        signOff.setApprovedBy(declinedBy);
        signOff.setDeclineReason(reason);
        return signOff;
    }

    private Project getOrCreateProject(String name, String createdBy, String migrationManagerName,
                                        Set<String> engineerEmails) {
        return projectRepository.findByNameIgnoreCase(name)
                .orElseGet(() -> projectRepository.save(new Project(name, createdBy, migrationManagerName, engineerEmails)));
    }

    private Server getOrCreateServer(Project project, String name, ProductType productType) {
        return serverRepository.findByProjectIdAndNameIgnoreCase(project.getId(), name)
                .orElseGet(() -> {
                    Server server = new Server(name);
                    server.setProject(project);
                    server.setProductType(productType);
                    return serverRepository.save(server);
                });
    }

    private WorkspaceCombination getOrCreateCombination(Server server, String name) {
        return combinationRepository.findByServerIdAndNameIgnoreCase(server.getId(), name)
                .orElseGet(() -> combinationRepository.save(new WorkspaceCombination(server, name)));
    }

    // Only seeds if this server has no pairs at all yet -- once real work has added/edited pairs,
    // never touch them again.
    private void seedPairsIfAbsent(Server server, String combinationName, List<WorkspacePair> pairs) {
        if (pairRepository.countByServerId(server.getId()) > 0) {
            return;
        }
        pairRepository.saveAll(pairs);
        server.setTotalPairCount(pairs.size());
        serverRepository.save(server);
        log.info("Seeded {} sample pairs for {} / {}.", pairs.size(), server.getName(), combinationName);
    }

    private void seedTicketIfAbsent(WorkspaceCombination combination, String ticketUrl, String createdBy,
                                     String key, String reporter, String summary, LocalDateTime reportedAt) {
        if (ticketRepository.existsByTicketUrlIgnoreCase(ticketUrl)) {
            return;
        }
        Ticket ticket = new Ticket(combination, ticketUrl, createdBy);
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setJiraKey(key);
        ticket.setJiraReporter(reporter);
        ticket.setJiraSummary(summary);
        ticket.setJiraCreatedAt(reportedAt);
        ticket.setCreatedAt(reportedAt);
        ticketRepository.save(ticket);
    }
}
