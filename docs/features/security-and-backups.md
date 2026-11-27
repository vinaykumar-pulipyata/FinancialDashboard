# Security and backups

The password and screen lock, and the automatic checked backups. How to restore one is in [Operations](../OPERATIONS.md).

[← Back to the README](../../README.md) · [All features](../FEATURES.txt)

- **Password and screen lock.** One user (`admin`). You set the password in the app the first time you open it; it is
  stored only as a BCrypt hash. The browser asks you to sign in, and a **Screen lock** covers the page as soon as it
  opens (type the password again to unlock), after 15 minutes without activity, and when you press the button. A refresh of a
  tab you have unlocked stays unlocked; a new tab, or a tab that was locked, opens locked. The server only listens on `127.0.0.1`.

- **Automatic checked backups.** A backup of the whole database is made when the app starts, when it closes, and daily
  if it stays open. Each is loaded into a throw-away database and compared with the live data, table by table, before
  it is kept. The last 30 days are kept, plus the first backup of every month for good, and an optional **second
  folder** (iCloud Drive, Dropbox, a USB drive) gets a copy of each. A red bar (under the page title, above Coming up) warns you when a backup fails or is
  more than two days old. Until you choose a second folder, a yellow bar says your backups are only on this computer; its
  **✕** hides it (kept in this browser, and it comes back if a second folder is chosen and later removed). The red
  warnings cannot be hidden. See [Backups in detail](../OPERATIONS.md#backups-in-detail).
