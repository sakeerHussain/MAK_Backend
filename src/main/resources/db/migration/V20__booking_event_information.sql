-- Lifecycle event notices are informational. Actionable reminders retain their urgency.
-- Preserve read/acknowledgement history while correcting historical badge/filter semantics.
UPDATE booking_alerts SET priority='Information' WHERE actionable=false AND priority<>'Information';
