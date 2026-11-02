CREATE VIEW v_student_sessions AS
SELECT s.id AS student_session_id, a.username, a.full_name, c.name AS class_name, c.type,
 s.machine_id, to_timestamp(s.signed_in_at / 1000.0) AS joined_time,
 to_timestamp(s.last_seen_at / 1000.0) AS last_seen_time,
 to_timestamp(s.ended_at / 1000.0) AS left_time, s.end_reason,
 to_timestamp(e.started_at / 1000.0) AS exam_started_time,
 to_timestamp(e.submitted_at / 1000.0) AS submitted_time,
 to_timestamp(e.closed_at / 1000.0) AS exam_closed_time
FROM student_sessions s JOIN accounts a ON a.id=s.account_id
JOIN class_sessions c ON c.id=s.class_id
LEFT JOIN exam_attempts e ON e.class_id=s.class_id AND e.account_id=s.account_id;

CREATE VIEW v_activity_history AS
SELECT r.id, s.class_id, a.username, a.full_name, s.machine_id, r.kind, r.details,
 to_timestamp(r.source_at / 1000.0) AS source_time,
 to_timestamp(r.observed_at / 1000.0) AS observed_time,
 to_timestamp(r.last_observed_at / 1000.0) AS last_observed_time,
 to_timestamp(r.observed_until / 1000.0) AS observed_until
FROM activity_records r JOIN student_sessions s ON s.id=r.student_session_id
JOIN accounts a ON a.id=s.account_id;

CREATE VIEW v_system_errors AS
SELECT e.id, s.class_id, a.username, a.full_name, s.machine_id, e.code, e.message,
 e.occurrence_count, to_timestamp(e.first_seen_at / 1000.0) AS first_time,
 to_timestamp(e.last_seen_at / 1000.0) AS last_time,
 to_timestamp(e.resolved_at / 1000.0) AS resolved_time
FROM system_errors e JOIN student_sessions s ON s.id=e.student_session_id
JOIN accounts a ON a.id=s.account_id;
