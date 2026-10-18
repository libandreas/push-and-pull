# Push & Pull: FTP, SFTP & WebDAV

Push & Pull is an FTP, SFTP and WebDAV client for Visual Studio Code and JetBrains IDEs. Upload or download project files and folders, and compare local and remote files directly from the editor. Other cloud storage services are available through rclone.

⬆⬇ Use toolbar buttons, context menus or keyboard shortcuts to transfer files. Run **Push & Pull: Create Configuration** to get started; rclone is downloaded automatically on first use.

![Push & Pull screenshot](https://ceres-assistant.com/screenshots-new/2026-05-26_17-47-39.webp)

## What You Can Do

- Upload files to your server.
- Download files from your server.
- Compare the online version of the current file with your local changes.
- Upload or download complete folders.
- Transfer multiple selected files or folders.
- Sync selected subfolders and deletions in both directions with Bsyncs.
- Use toolbar buttons, context menus, or keyboard shortcuts.
- See live transfer progress inside the editor.
- Connect to FTP, SFTP, WebDAV, S3, and many other services supported by rclone.

Push & Pull uses rclone for the actual file transfers. You only need to describe your server once in an `rclone.conf` file inside your project.

## Keyboard Shortcuts

- **Ctrl+Up**: upload the current file.
- **Ctrl+Down**: download the current file.
- On macOS, use **Cmd+Up** and **Cmd+Down**.

Folder transfers and multiple selections are available from the file or project context menu.

## Project Setup

Run **Push & Pull: Create Configuration** from the Command Palette in VS Code or **Find Action** in JetBrains IDEs. In JetBrains, it is also available under **Tools → Push & Pull**. It creates an `rclone.conf` in your project root. Fill in your server details and destination folder. If the file already exists, the command opens it without replacing it.

The template includes an FTP connection with explicit TLS (FTPS) and the required `my-project` alias. You can also create `rclone.conf` manually or configure another connection type listed below.

Push & Pull uses this file to understand:

- where your server is,
- which connection type to use,
- which username and port to use,
- where the project should be uploaded,
- and how the connection should be authenticated.

Add `rclone.conf` to your `.gitignore` so passwords, access tokens, and server details are not committed to your repository:

```gitignore
rclone.conf
```

## Basic `rclone.conf` Example

The following example creates an FTP connection with explicit TLS (FTPS) named `my-server` and the required `my-project` destination:

```ini
[my-server]
type = ftp
host = example.com
user = my-user
port = 21
explicit_tls = true
pass-visible = my-password

[my-project]
type = alias
remote = my-server:/httpdocs
```

Replace the host, username, password and `/httpdocs` destination with your server details. Use `explicit_tls = false` only if your server requires plain FTP.

## The `my-project` Destination

Push & Pull sends project files to a destination named `my-project:`.

The `my-project` alias in the example points to the folder where your project should be deployed:

```ini
[my-project]
type = alias
remote = my-server:/httpdocs
```

In this example:

- `my-server` is the FTP connection.
- `/httpdocs` is the website folder on the server.
- `my-project:` represents that folder inside Push & Pull.

If you upload this local file:

```text
images/logo.png
```

Push & Pull uploads it to:

```text
my-project:/images/logo.png
```

Transfers preserve project-relative paths. They run when you choose Push or Pull and do not delete extra files at the destination.

You can choose any name for the real server connection, but the deploy alias must be named `my-project`.

## How `pass-visible` Works

The `pass-visible` option lets you enter the real password in a readable form:

```ini
pass-visible = my-password
```

Before every upload, download, or comparison, Push & Pull finds `pass-visible` and asks rclone to obscure that password. It then adds or updates the `pass` option directly below it:

```ini
pass-visible = my-password
pass = obscured-password-created-by-rclone
```

The generated `pass` value is the format rclone expects for the connection. You do not need to run `rclone obscure` yourself or manually copy the generated value.

If the visible password changes, Push & Pull generates a new `pass` value on the next upload, download, or comparison.

`pass-visible` still contains the real password as plain text. Always keep `rclone.conf` private and exclude it from Git.

## Other Connection Types

Push & Pull can use the connection types supported by rclone, including:

- FTP and FTPS
- SFTP over SSH
- WebDAV
- S3 and S3-compatible storage
- SMB
- Azure Blob Storage
- Google Cloud Storage
- Google Drive
- OneDrive
- Backblaze B2
- Swift

The server section changes depending on the connection type, but the `my-project` alias works in the same way.

For example, a WebDAV connection can be used as the real remote while `my-project` continues to point to the correct website folder.

## Uploading and Downloading

### Read-Only Website Downloads

To download and compare files directly from a website, create `.skia/read-only-url.txt` with the website URL:

```text
https://example.com
```

This downloads the public content served at each URL for file downloads and comparisons, not server-side source code such as PHP. Uploads still use rclone, and folder downloads are unavailable.

To upload a file or folder:

1. Select it in the editor or project explorer.
2. Choose `Push (Upload)` or `Push Folder (Upload)`.
3. Follow the live progress notification.

To download a file or folder:

1. Select its local location in the project.
2. Choose `Pull (Download)` or `Pull Folder (Download)`.
3. Push & Pull downloads the matching path from `my-project:`.

For example, downloading:

```text
css/style.css
```

reads the remote file from:

```text
my-project:/css/style.css
```

and writes it back to the same location in the local project.

## Syncing Subfolders

In VS Code or JetBrains IDEs, right-click a project subfolder and choose **Sync Folder (Bsyncs)** below Push and Pull. In JetBrains, it is also available under **Tools → Push & Pull**. It runs `rclone bisync` directly for that subfolder and its matching server path. The project root and individual files cannot be synced with this command. Read-only website mode is not supported.

An information notification displays: **Bsyncs can delete local files deleted on the server, and server files deleted locally. Use with caution.** It uses VS Code's `showInformationMessage` or the existing Push & Pull information notification in JetBrains. The transfer then uses the same progress display as Push and Pull. There is no confirmation button, preview or automatic backup. Bsyncs starts when selected. Closing a notification hides it and does not cancel the transfer.

On the first run, `--resync-mode newer` initializes the history and combines files from both sides. Later runs propagate changes and deletions based on that history. If both copies changed, `--conflict-resolve newer` prefers the newer modification time. This is change tracking: a change on just one side can propagate even if its timestamp became older. Unresolved conflicts keep both versions using rclone's numbered conflict names.

Deleting a server file can therefore delete the unchanged local copy, and deleting a local file can delete its server copy. If the surviving copy changed, rclone normally preserves it and copies it back. Sync needs reliable server modification times. Equal dates and sizes can conceal content changes, especially on FTP servers without checksums.

Rclone keeps its history in the extension's private `bisync` storage for each project and subfolder. Use the same selected folders on subsequent runs and avoid overlapping parent/child syncs. Existing history is never automatically reset after an error; rclone reports when manual recovery is needed. Its built-in locks and deletion limits remain enabled.

VS Code and JetBrains keep separate sync histories. Use one editor for a given folder's ongoing Bsyncs sessions.

`rclone.conf` is always excluded from Sync and folder transfers; direct Push/Pull of that file is blocked. It is read locally to configure the connection.

## Comparing Online and Local Files

Open a local file and choose `Compare Online with Local`.

Push & Pull downloads the matching online file to the editor's private storage and opens the built-in diff viewer:

- `Online` appears on the left.
- `Local` appears on the right.
- Unsaved changes in the current editor are included.
- Your local file is not overwritten.

## Finding the Project Configuration

Push & Pull normally uses the `rclone.conf` in the project root.

If the selected file belongs to a nested project, Push & Pull searches its parent folders for the nearest `rclone.conf`. This allows different projects to use different servers and deployment folders.

## Transfer Settings

Open your editor settings and search for `Push & Pull`.

Available settings:

- `Transfers`: how many files rclone can transfer at the same time. The default is `2`.
- `Checkers`: how many checks rclone can run at the same time. The default is `4`.

The default values are suitable for most servers. Higher values may make transfers faster on powerful servers, while lower values may work better on limited shared hosting.

## rclone Installation

You do not need to install rclone manually.

On first use, Push & Pull downloads a compatible rclone version for your operating system. It keeps that version inside the editor's private storage and checks periodically for updates.

If an update check cannot connect to the internet, Push & Pull continues using the previously downloaded version when available.

## Transfer Progress and Errors

During an upload or download, Push & Pull shows the latest transfer information, including transferred size, percentage, speed, and estimated time when rclone provides them.

When the transfer finishes, you receive a completion notification. If it fails, the notification shows the latest useful error returned by rclone.

## Bug Reports and Feedback

https://ceres-assistant.com/web/contact.php
