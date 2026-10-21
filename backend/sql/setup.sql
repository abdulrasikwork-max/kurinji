-- One-time MySQL setup for local development. Run as the MySQL root (or another admin) user.
-- From the backend folder (works in PowerShell, cmd and bash):
--   mysql -u root -p -e "source sql/setup.sql"
-- Replace <choose-a-password> with your own password BEFORE running, and use the same value
-- for DB_PASSWORD. Tables are created automatically by Flyway when the backend starts.

CREATE DATABASE IF NOT EXISTS safesphere
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'safesphere'@'localhost' IDENTIFIED BY '<choose-a-password>';
GRANT ALL PRIVILEGES ON safesphere.* TO 'safesphere'@'localhost';
FLUSH PRIVILEGES;
