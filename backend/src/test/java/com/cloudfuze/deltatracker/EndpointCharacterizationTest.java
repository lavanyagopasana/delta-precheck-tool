package com.cloudfuze.deltatracker;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Characterization snapshots for the five endpoints the refactor will touch. Seeds a deterministic
// graph (fixed timestamps; auto-increment ids reset each test so ids are stable run-to-run) and
// golden-files the exact JSON. NOT @Transactional -- each test resets + commits a fresh graph so
// the endpoints (which run their own transactions) read committed data with predictable ids.
//
// Pre-checks/sign-offs/Delta/tickets are all scoped to a WorkspaceCombination now, not a Server
// directly -- a server can have several combinations, each migrated independently (see the
// per-combination migration in decisions.md). The seed below gives each server exactly one
// combination so the shape stays simple, but every entity hangs off the combination, not the server.
// @TestPropertySource wins over EVERY other property source, including a file:./application.properties
// sitting in the working directory a developer launches tests from -- @ActiveProfiles("test") alone
// isn't enough, since Spring Boot's config-data precedence ranks location (file:./ vs classpath:/)
// above profile-specificity. Without this, a local-dev override file that happens to set
// spring.datasource.url (or azure.client-id, etc.) silently wins over application-test.properties'
// H2/no-auth setup, and this test stops being hermetic without any visible warning.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:/application-test.properties")
class EndpointCharacterizationTest {

    private static final LocalDateTime T_CREATED = LocalDateTime.of(2026, 1, 1, 9, 0, 0);
    private static final LocalDateTime T_SUBMITTED = LocalDateTime.of(2026, 1, 2, 10, 0, 0);
    private static final LocalDateTime T_MM_APPROVED = LocalDateTime.of(2026, 1, 3, 11, 0, 0);
    private static final LocalDateTime T_DEV_APPROVED = LocalDateTime.of(2026, 1, 3, 12, 0, 0);
    private static final LocalDateTime T_QA_APPROVED = LocalDateTime.of(2026, 1, 3, 13, 0, 0);
    private static final LocalDateTime T_DELTA_INIT = LocalDateTime.of(2026, 1, 4, 12, 0, 0);
    private static final LocalDateTime T_DELTA_START = LocalDateTime.of(2026, 1, 5, 13, 0, 0);
    private static final LocalDateTime T_DELTA_FINISH = LocalDateTime.of(2026, 1, 6, 14, 0, 0);
    private static final LocalDateTime T_ESCALATION = LocalDateTime.of(2026, 1, 7, 15, 0, 0);
    private static final LocalDateTime T_SIGN_1 = LocalDateTime.of(2026, 1, 2, 10, 1, 0);
    private static final LocalDateTime T_SIGN_2 = LocalDateTime.of(2026, 1, 2, 10, 2, 0);
    private static final LocalDateTime T_SIGN_3 = LocalDateTime.of(2026, 1, 2, 10, 3, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private WorkspaceCombinationRepository combinationRepository;
    @Autowired private WorkspacePairRepository workspacePairRepository;
    @Autowired private PreCheckSubmissionRepository submissionRepository;
    @Autowired private SignOffRepository signOffRepository;
    @Autowired private TicketRepository ticketRepository;

    private Long alphaProjectId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {
                "tickets", "sign_offs", "precheck_submissions", "precheck_items", "workspace_combinations",
                "workspace_pairs", "project_engineers", "servers", "projects", "app_users" }) {
            jdbc.execute("TRUNCATE TABLE " + table);
            if (!table.equals("project_engineers")) {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1");
            }
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        seed();
    }

    private void seed() {
        // Project "Alpha Project" -- has two servers exercising two lifecycle states.
        Set<String> alphaEngineers = new LinkedHashSet<>(Set.of("alice@cloudfuze.com"));
        Project alpha = new Project("Alpha Project", "alice@cloudfuze.com",
                "mgr@cloudfuze.com", alphaEngineers);
        alpha.setCreatedAt(T_CREATED);
        alpha = projectRepository.save(alpha);
        alphaProjectId = alpha.getId();

        // Project "Beta Project" -- empty (no servers), no Migration Manager.
        Project beta = new Project("Beta Project", "bob@cloudfuze.com", null, null);
        beta.setCreatedAt(T_CREATED);
        projectRepository.save(beta);

        // Server 1, one combination: submitted, Migration Manager approved, Dev/QA still pending (mid-chain).
        Server s1 = new Server("SRV-ALPHA-1");
        s1.setProject(alpha);
        s1.setStatus(PairStatus.DELTA_READY);
        s1 = serverRepository.save(s1);
        WorkspaceCombination c1 = combinationRepository.save(new WorkspaceCombination(s1, "Combo A"));
        workspacePairRepository.save(pair(s1, "src1@a.com", "dst1@b.com", c1.getName()));
        workspacePairRepository.save(pair(s1, "src2@a.com", "dst2@b.com", c1.getName()));
        saveSubmission(c1, "alice@cloudfuze.com");
        saveSignOff(c1, SignOffRole.MIGRATION_LEAD, "mgr@cloudfuze.com", SignOffStatus.APPROVED,
                "mgr@cloudfuze.com", T_MM_APPROVED, T_SIGN_1, null);
        saveSignOff(c1, SignOffRole.DEV_LEAD, "Any Dev Lead", SignOffStatus.PENDING, null, null, T_SIGN_2, null);
        saveSignOff(c1, SignOffRole.QA_LEAD, "Any QA Lead", SignOffStatus.PENDING, null, null, T_SIGN_3, null);

        Ticket t1 = new Ticket(c1, "https://jira.example.com/browse/JIRA-101", "alice@cloudfuze.com");
        t1.setStatus(TicketStatus.OPEN);
        t1.setCreatedAt(T_ESCALATION);
        ticketRepository.save(t1);

        // Server 2, one combination: fully approved and Delta finished.
        Server s2 = new Server("SRV-ALPHA-2");
        s2.setProject(alpha);
        s2.setStatus(PairStatus.DELTA_READY);
        s2 = serverRepository.save(s2);
        WorkspaceCombination c2 = new WorkspaceCombination(s2, "Combo A");
        c2.setDeltaInitiatedAt(T_DELTA_INIT);
        c2.setDeltaInitiatedBy("alice@cloudfuze.com");
        c2.setDeltaStartedAt(T_DELTA_START);
        c2.setDeltaStartedBy("alice@cloudfuze.com");
        c2.setDeltaFinishedAt(T_DELTA_FINISH);
        c2.setDeltaFinishedBy("alice@cloudfuze.com");
        c2 = combinationRepository.save(c2);
        workspacePairRepository.save(pair(s2, "src3@a.com", "dst3@b.com", c2.getName()));
        saveSubmission(c2, "alice@cloudfuze.com");
        saveSignOff(c2, SignOffRole.MIGRATION_LEAD, "mgr@cloudfuze.com", SignOffStatus.APPROVED,
                "mgr@cloudfuze.com", T_MM_APPROVED, T_SIGN_1, null);
        saveSignOff(c2, SignOffRole.DEV_LEAD, "Any Dev Lead", SignOffStatus.APPROVED,
                "dev@cloudfuze.com", T_DEV_APPROVED, T_SIGN_2, Boolean.TRUE);
        saveSignOff(c2, SignOffRole.QA_LEAD, "Any QA Lead", SignOffStatus.APPROVED,
                "qa@cloudfuze.com", T_QA_APPROVED, T_SIGN_3, null);
    }

    private WorkspacePair pair(Server server, String sourceEmail, String destinationEmail, String combination) {
        WorkspacePair p = new WorkspacePair(server, sourceEmail, destinationEmail);
        p.setCombination(combination);
        return p;
    }

    private void saveSubmission(WorkspaceCombination combination, String who) {
        PreCheckSubmission sub = new PreCheckSubmission(combination);
        sub.setStatus(SubmissionStatus.SUBMITTED);
        sub.setSubmittedBy(who);
        sub.setSubmittedAt(T_SUBMITTED);
        sub.setStartedByEmail(who);
        submissionRepository.save(sub);
    }

    private void saveSignOff(WorkspaceCombination combination, SignOffRole role, String signedBy, SignOffStatus status,
                             String approvedBy, LocalDateTime approvedAt, LocalDateTime signedAt, Boolean qaRequired) {
        SignOff so = new SignOff(combination, role, signedBy);
        so.setStatus(status);
        so.setApprovedBy(approvedBy);
        so.setApprovedAt(approvedAt);
        so.setSignedAt(signedAt);
        so.setQaRequired(qaRequired);
        signOffRepository.save(so);
    }

    private String getJson(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void dashboardSummary() throws Exception {
        JsonSnapshot.match("dashboard-summary", getJson("/api/dashboard/summary"));
    }

    @Test
    void projectsList() throws Exception {
        JsonSnapshot.match("projects-list", getJson("/api/projects"));
    }

    @Test
    void projectDetail() throws Exception {
        JsonSnapshot.match("project-detail", getJson("/api/projects/" + alphaProjectId));
    }

    @Test
    void ticketsList() throws Exception {
        JsonSnapshot.match("tickets-list", getJson("/api/tickets"));
    }

    @Test
    void approvalsList() throws Exception {
        JsonSnapshot.match("approvals-list", getJson("/api/signoff-approvals"));
    }
}
