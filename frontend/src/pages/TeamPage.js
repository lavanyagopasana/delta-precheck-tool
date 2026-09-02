import React, { useEffect, useState } from "react";
import { getRoster, getTeams } from "../api/client";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { emailLocalPart } from "../utils/format";

const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

// Same derivation AdminUsersPage's Teams panel uses: a team named for its manager(s) reads better
// than the stored placeholder name ("Team 4") nobody but an admin ever typed in.
const teamDisplayName = (team) => {
  const managers = team.managerEmails || [];
  if (managers.length === 0) return team.name;
  return `${managers.map(emailLocalPart).join("/")} team`;
};

const TeamIcon = (props) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" {...props}>
    <circle cx="9" cy="8" r="3" />
    <path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" />
    <circle cx="17" cy="8" r="2.5" />
    <path d="M17 14.2c2.3.5 4 2.6 4 5.8" />
  </svg>
);

function PersonChip({ email }) {
  return (
    <span className="engineer-chip" style={{ cursor: "default" }}>
      <span className="person-avatar">{initials(email)}</span>
      {email}
    </span>
  );
}

// Mirrors ProjectDetailsPage's header: an icon+title row, the manager set off to one side, the
// engineers below as the same read-only chip list used there -- so a team reads as the same kind
// of "people on this thing" fact everywhere it shows up in the app.
function TeamCard({ team, heading }) {
  const managers = team.managerEmails || [];
  const engineers = team.engineerEmails || [];
  const unmanaged = managers.length === 0;

  return (
    <div className="detail-header" style={{ marginBottom: 16 }}>
      <div className="detail-header-top detail-header-top--split">
        <span className="detail-header-icon">
          <TeamIcon width={18} height={18} />
        </span>
        <h2 className="detail-header-title">{heading || teamDisplayName(team)}</h2>

        <div className="project-manager">
          <span className="detail-fact-label">
            Migration Manager{managers.length === 1 ? "" : "s"}
          </span>
          {unmanaged ? (
            <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not assigned yet</span>
          ) : (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
              {managers.map((email) => (
                <div key={email} style={{ display: "flex", alignItems: "center", gap: 9 }}>
                  <span className="person-avatar">{initials(email)}</span>
                  <span style={{ fontSize: 13.5, fontWeight: 600 }}>{email}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="project-people">
        <div className="project-people-block project-people-block--grow">
          <div className="assign-section-head">
            <span className="detail-fact-label">Engineers ({engineers.length})</span>
            {unmanaged && <span className="team-card-tag team-card-tag--warn">no manager yet</span>}
          </div>
          {engineers.length ? (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {engineers.map((email) => (
                <PersonChip key={email} email={email} />
              ))}
            </div>
          ) : (
            <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>None yet</span>
          )}
        </div>
      </div>
    </div>
  );
}


/**
 * The sign-off approvers who are not part of any team.
 *
 * Shown to everybody, and deliberately not filtered the way the team cards are. Teams exist to
 * decide which engineers a Migration Manager may assign; the Dev Lead and QA Lead are the 2nd and
 * 3rd steps of the sign-off chain and act across every team, so there is no "your" Dev Lead to
 * narrow this to. Before this existed a Dev Lead opening this page was told "You are not in any
 * team yet", which was technically true and completely unhelpful.
 *
 * An empty list says so plainly rather than rendering nothing: "no QA Lead has been added" is the
 * reason approvals will stall at that step, and it should be visible here rather than discovered
 * when a chain cannot advance.
 */
function ApproverCard({ title, step, people }) {
  return (
    <div className="detail-header" style={{ marginBottom: 16 }}>
      <div className="detail-header-top detail-header-top--split">
        <span className="detail-header-icon">
          <TeamIcon width={18} height={18} />
        </span>
        <h2 className="detail-header-title">{title}</h2>
        <div className="project-manager">
          <span className="detail-fact-label">Sign-off step</span>
          <span style={{ fontSize: 13.5, fontWeight: 600 }}>{step}</span>
        </div>
      </div>

      <div className="project-people">
        <div className="project-people-block project-people-block--grow">
          <div className="assign-section-head">
            <span className="detail-fact-label">
              {title} ({people.length})
            </span>
            {people.length === 0 && (
              <span className="team-card-tag team-card-tag--warn">none added yet</span>
            )}
          </div>
          {people.length ? (
            <>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                {people.map((email) => (
                  <PersonChip key={email} email={email} />
                ))}
              </div>
              {/* Worth stating because the card looks like a list of people who must all act.
                  SignOffService.isEligible checks the DEV_LEAD / QA_LEAD role, not a named person
                  (unlike the Migration Manager step, which is pinned to the one the project names),
                  and the chain holds a single row per role -- so the first of them to approve
                  satisfies the step for everybody. */}
              <p className="note-hint" style={{ marginTop: 10, marginBottom: 0 }}>
                {people.length === 1
                  ? "This step is open to anyone holding the role, not to a named person — right now that is one person."
                  : `Any one of these ${people.length} can approve — the step is satisfied by whoever acts first, not by all of them.`}
              </p>
            </>
          ) : (
            <span className="warn-hint" style={{ marginBottom: 0 }}>
              Nobody holds this role yet — an admin adds one under Manage Access. Until then a
              sign-off chain cannot get past this step.
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// Read-only: a manager or engineer sees only their own team (who manages it, who else is on it) --
// not every team in the company. Nobody edits membership here either; that stays an admin-only job
// on the Manage Access page, which is also where an admin goes to see every team at once.
export default function TeamPage() {
  const currentUser = useCurrentUser();
  const [teams, setTeams] = useState([]);
  const [roster, setRoster] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    getTeams()
      .then((data) => {
        setTeams(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load teams."))
      .finally(() => setLoading(false));
  }, []);

  // Separate from the teams call and deliberately not blocking it: the approver cards and the team
  // cards are independent facts, so one failing should not blank the other. A failed roster simply
  // leaves the approver cards out rather than showing them empty, which would read as "no leads
  // exist" when the truth is we could not ask.
  useEffect(() => {
    getRoster()
      .then(setRoster)
      .catch(() => setRoster(null));
  }, []);

  if (loading && teams.length === 0) return <p>Loading teams...</p>;

  const isAdmin = !AUTH_CONFIGURED || currentUser?.role === "ADMIN";
  const myEmail = (AUTH_CONFIGURED ? currentUser?.email : "").toLowerCase();
  const onTeam = (t) =>
    (t.managerEmails || []).some((e) => e.toLowerCase() === myEmail) ||
    (t.engineerEmails || []).some((e) => e.toLowerCase() === myEmail);
  // Admins aren't members of any team, but they're the ones who set teams up -- showing every team
  // to them (rather than nothing) matches the full Teams panel they already have on Manage Access.
  const visibleTeams = isAdmin ? teams : teams.filter(onTeam);

  return (
    <div>
      <div className="detail-header-top" style={{ marginBottom: 18 }}>
        <span className="detail-header-icon">
          <TeamIcon width={20} height={20} />
        </span>
        <h2 className="detail-header-title">{isAdmin ? "Teams" : "My Team"}</h2>
      </div>

      {error && <div className="inline-hint" style={{ marginBottom: 12 }}>{error}</div>}

      {!error && visibleTeams.length === 0 ? (
        <div className="note-hint">
          {isAdmin
            ? "No teams yet. Ask an admin to set one up under Manage Access."
            : "You are not on a migration team. The approvers below sign off across every team."}
        </div>
      ) : (
        visibleTeams.map((t) => (
          <TeamCard key={t.id} team={t} heading={isAdmin ? teamDisplayName(t) : undefined} />
        ))
      )}

      {roster && (
        <>
          <div className="detail-header-top" style={{ margin: "26px 0 14px" }}>
            <span className="detail-header-icon">
              <TeamIcon width={20} height={20} />
            </span>
            <h2 className="detail-header-title">Approvers</h2>
          </div>
          {/* Not scoped to the viewer, unlike the teams above: these two roles approve across every
              team, so everyone sees the same list. */}
          <p className="note-hint" style={{ marginBottom: 14 }}>
            These roles are not part of any team — they approve for every team. A pre-check goes
            Migration Manager → Dev Lead → QA Lead, and the Dev Lead decides at approval time
            whether the QA Lead step is needed at all.
          </p>
          <ApproverCard title="Dev Leads" step="2nd — after the Migration Manager" people={roster.devLeads || []} />
          <ApproverCard title="QA Leads" step="3rd — if the Dev Lead requires it" people={roster.qaLeads || []} />
        </>
      )}
    </div>
  );
}
