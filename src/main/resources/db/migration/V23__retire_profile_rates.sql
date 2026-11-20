-- Rates move to the Rate Card system; operational profiles no longer hold financial terms.
-- Production holds no data yet (client data is bulk-uploaded after this release).
ALTER TABLE vehicles DROP COLUMN hourly_rate;
ALTER TABLE vehicles DROP COLUMN daily_rate;
ALTER TABLE vehicles DROP COLUMN monthly_rate;
ALTER TABLE vehicles DROP COLUMN rate_currency;

ALTER TABLE drivers DROP COLUMN hourly_rate;
ALTER TABLE drivers DROP COLUMN daily_rate;
ALTER TABLE drivers DROP COLUMN monthly_rate;

ALTER TABLE employees DROP COLUMN hourly_rate;
ALTER TABLE employees DROP COLUMN daily_rate;
ALTER TABLE employees DROP COLUMN monthly_salary;
