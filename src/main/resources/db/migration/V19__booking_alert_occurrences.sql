-- Keep the original alert and its recipient history when a condition returns.
-- alert_key identifies one occurrence; condition_key identifies the reminder condition.
ALTER TABLE booking_alerts ADD COLUMN condition_key VARCHAR(250);
UPDATE booking_alerts SET condition_key=alert_key;
ALTER TABLE booking_alerts ALTER COLUMN condition_key SET NOT NULL;
CREATE INDEX ix_booking_alert_condition ON booking_alerts(condition_key,resolved_at);
