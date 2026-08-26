-- Seeds the 6 delivery teams and their 31 members.
--
-- Use this when you want the teams in place WITHOUT waiting for a deploy or an admin CSV upload.
-- It is the same data as teams-roster.csv, expressed as SQL. Safe to run more than once.
--
--   docker compose exec -T db psql -U postgres -d delta_migration_tracker < docs/seed/teams-roster.sql
--   -- or --
--   psql "$DB_URL" -f docs/seed/teams-roster.sql
--
-- PREREQUISITE: the `teams` table and `app_users.team_id` column must already exist, which means the
-- backend carrying the Team entity has to have started at least once (ddl-auto=update creates them
-- on boot). Run this against an older build and it fails with `relation "teams" does not exist`.
--
-- Deliberately does NOT touch anyone's ADMIN row: the DO UPDATE below skips admins, so seeding can
-- never demote the person running it out of Manage Access. Emails are lowercased because that is how
-- AppUser stores them and every lookup in the app is case-insensitive against that form.
--
-- Three addresses differ from the list this was transcribed from, which could not have worked as
-- given (email is the only identity key in this app, so a typo creates an account nobody can sign in
-- as). CONFIRM THESE AGAINST THE REAL DIRECTORY -- the first is a MANAGER, so Team 4's whole engineer
-- filter depends on it:
--   Lakshmi.Prasanna@doudfuze.com   -> lakshmi.prasanna@cloudfuze.com    ("doudfuze")
--   Ganesh.Kondameedi@icloudfuze.com -> ganesh.kondameedi@cloudfuze.com  ("icloudfuze")
--   "Davidraj. Dumpala@cloudfuze.com" -> davidraj.dumpala@cloudfuze.com  (space in the local part)

BEGIN;

INSERT INTO teams (name, created_by, created_at) VALUES
    ('Team 1', 'seed', now()),
    ('Team 2', 'seed', now()),
    ('Team 3', 'seed', now()),
    ('Team 4', 'seed', now()),
    ('Team 5', 'seed', now()),
    ('Team 6', 'seed', now())
ON CONFLICT (name) DO NOTHING;

-- One statement per person, joined to their team by name so the generated team ids never need to be
-- known here. Teams 5 and 6 carry two managers each on purpose: both see that team's engineers.
WITH roster(email, role, team_name) AS (VALUES
    -- Team 1
    ('harika.velidi@cloudfuze.com',        'MIGRATION_MANAGER',  'Team 1'),
    ('siva.kota@cloudfuze.com',            'MIGRATION_ENGINEER', 'Team 1'),
    ('ravi.hemanth@cloudfuze.com',         'MIGRATION_ENGINEER', 'Team 1'),
    ('meena.lakshmi@cloudfuze.com',        'MIGRATION_ENGINEER', 'Team 1'),
    -- Team 2
    ('raghu.yellani@cloudfuze.com',        'MIGRATION_MANAGER',  'Team 2'),
    ('sriram.ramakrishnan@cloudfuze.com',  'MIGRATION_ENGINEER', 'Team 2'),
    ('vineetha.yenti@cloudfuze.com',       'MIGRATION_ENGINEER', 'Team 2'),
    ('ramana.reddy@cloudfuze.com',         'MIGRATION_ENGINEER', 'Team 2'),
    -- Team 3
    ('sravan.kesaram@cloudfuze.com',       'MIGRATION_MANAGER',  'Team 3'),
    ('swaroop@cloudfuze.com',              'MIGRATION_ENGINEER', 'Team 3'),
    ('dathu.kaluvala@cloudfuze.com',       'MIGRATION_ENGINEER', 'Team 3'),
    ('saikumar.kustapuram@cloudfuze.com',  'MIGRATION_ENGINEER', 'Team 3'),
    -- Team 4  (manager address corrected from "doudfuze")
    ('lakshmi.prasanna@cloudfuze.com',     'MIGRATION_MANAGER',  'Team 4'),
    ('lakshmareddy@cloudfuze.com',         'MIGRATION_ENGINEER', 'Team 4'),
    ('chaitanya.gupta@cloudfuze.com',      'MIGRATION_ENGINEER', 'Team 4'),
    ('davidraj.dumpala@cloudfuze.com',     'MIGRATION_ENGINEER', 'Team 4'),
    ('ganesh.kondameedi@cloudfuze.com',    'MIGRATION_ENGINEER', 'Team 4'),
    ('harshith.kaduluri@cloudfuze.com',    'MIGRATION_ENGINEER', 'Team 4'),
    -- Team 5  (two managers)
    ('abhishikth.yenugula@cloudfuze.com',  'MIGRATION_MANAGER',  'Team 5'),
    ('ajay.singh@cloudfuze.com',           'MIGRATION_MANAGER',  'Team 5'),
    ('neelima.krotta@cloudfuze.com',       'MIGRATION_ENGINEER', 'Team 5'),
    ('amulya.anapuram@cloudfuze.com',      'MIGRATION_ENGINEER', 'Team 5'),
    ('ranadeep.muddam@cloudfuze.com',      'MIGRATION_ENGINEER', 'Team 5'),
    ('vijendar.burgula@cloudfuze.com',     'MIGRATION_ENGINEER', 'Team 5'),
    ('habeebunnisa.begum@cloudfuze.com',   'MIGRATION_ENGINEER', 'Team 5'),
    -- Team 6  (two managers)
    ('abhishek.sakala@cloudfuze.com',      'MIGRATION_MANAGER',  'Team 6'),
    ('pranavi@cloudfuze.com',              'MIGRATION_MANAGER',  'Team 6'),
    ('chandra.mouli@cloudfuze.com',        'MIGRATION_ENGINEER', 'Team 6'),
    ('arun@cloudfuze.com',                 'MIGRATION_ENGINEER', 'Team 6'),
    ('manoj.bathula@cloudfuze.com',        'MIGRATION_ENGINEER', 'Team 6'),
    ('pallavi.kosuvaripalli@cloudfuze.com','MIGRATION_ENGINEER', 'Team 6')
)
INSERT INTO app_users (email, role, team_id, added_by, added_at)
SELECT r.email, r.role, t.id, 'seed', now()
FROM roster r
JOIN teams t ON t.name = r.team_name
ON CONFLICT (email) DO UPDATE
    SET role    = EXCLUDED.role,
        team_id = EXCLUDED.team_id
    -- Never demote an existing admin. Anyone already ADMIN keeps that role and stays team-less,
    -- which is correct: admins sit outside the team structure.
    WHERE app_users.role <> 'ADMIN';

COMMIT;

-- Verify: expect 6 rows, and 8 managers / 23 engineers across them.
--   SELECT t.name,
--          count(*) FILTER (WHERE u.role = 'MIGRATION_MANAGER')  AS managers,
--          count(*) FILTER (WHERE u.role = 'MIGRATION_ENGINEER') AS engineers
--   FROM teams t LEFT JOIN app_users u ON u.team_id = t.id
--   GROUP BY t.name ORDER BY t.name;
