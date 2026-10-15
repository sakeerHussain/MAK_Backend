# Persistent local Windows startup

Run these commands from the backend folder in either Command Prompt or PowerShell.

```text
backend.cmd setup
backend.cmd check
backend.cmd start
```

In PowerShell, use the `.` prefix: `.\backend.cmd setup`, `.\backend.cmd check`, and `.\backend.cmd start`.

## One-time setup

First ensure the PostgreSQL server is running, the `car_rental` database exists, and you know the login password. Setup does not create users, reset passwords, change database permissions, or test connectivity.

Run `.\backend.cmd setup` as your normal Windows user. Accept the defaults for host `localhost`, port `5432`, database `car_rental`, and username `postgres` if those match pgAdmin. Enter the working PostgreSQL password at the hidden prompt. The script detects an installed Java 21 JDK and lets you confirm its directory.

Choose `Y` when asked about SMTP to save email settings as well. Enter the provider hostname, port, username, password/app password, and permitted sender email. For this project's previously confirmed mail server, the hostname is `mail.drishtiinnova.com`, port is `465`, implicit SSL is `Y`, and STARTTLS is `N`. Use your actual sender username and sender email. A hostname is not a sender email address. Setup/check do not send email. Enabling SMTP when you start the backend allows its existing outbox worker to deliver queued messages.

If you choose `N`, saved email settings are kept; on first setup, email remains disabled. Setup can be repeated after a database/SMTP password or JDK directory changes.

## What persists

Settings live at `%LOCALAPPDATA%\MAK\backend\settings.xml`, outside the repository and its OneDrive folder. Database and SMTP passwords are serialized as encrypted Windows credential objects using DPAPI, not plain-text environment files. Nonsecret connection settings remain readable. The saved credentials work for the Windows account/computer that created them; run setup again on another computer or under another account.

The launcher loads these settings only into its own process and the Maven/backend child processes. It does not set machine-wide environment variables, change PostgreSQL authentication, or modify other projects' Java installations. Saved DB/SMTP settings take precedence over old values in the starting terminal. Existing unrelated variables, such as upload paths and frontend URL, are inherited normally. No administrator elevation is required.

`backend.cmd` selects RemoteSigned execution policy for its child PowerShell process only; it does not change the machine/user execution-policy configuration. Organizational policies still take precedence.

## Starting and checking

After setup, use `.\backend.cmd start` in any new terminal, including after a reboot. The launcher selects Java 21, runs Maven `clean spring-boot:run`, and stops if required configuration is missing. Cleaning removes generated classes from previous Java versions and does not remove database records or `uploads/`. Use Ctrl+C to stop the server. `.\backend.cmd start -Offline` uses cached Maven dependencies.

`.\backend.cmd check` verifies that local credentials can be decrypted and that a Java 21 JDK exists. It does not confirm the database password, PostgreSQL access, SMTP delivery, or application startup. Successful application startup still requires `Started VehicleRentalApplication` in the log.

The ordinary `mvnw.cmd` command still supports the existing environment-variable workflow, including on deployed servers. To use the saved Windows configuration, start with `backend.cmd`.

## VS Code and compiled classes

Setup configures the project runtime as `JavaSE-21`, enables automatic Maven configuration updates, and disables Java auto-build so the editor does not replace Maven's generated classes. It updates backend `.vscode/settings.json` and, when the parent contains both frontend/backend, the parent workspace settings, preserving unrelated settings. Reload the VS Code window once after setup. Maven remains responsible for compilation. Java hot-code replacement that depends on editor auto-build will be unavailable.

The Java language server may use its bundled newer Java runtime; the project runtime/compile target stays Java 21, matching `pom.xml`. Do not force the language server itself to Java 21 if the installed extension requires a newer version. If settings contain JSON comments that the setup script cannot parse, it leaves them intact and prints a warning; set `java.autobuild.enabled` to `false`, map `JavaSE-21` in `java.configuration.runtimes`, and set `java.configuration.updateBuildConfiguration` to `automatic` manually.

This is a local Windows development workflow. Deployment should continue to use its own environment/secret manager.
