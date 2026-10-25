# RZilla: FTP, SFTP & WebDAV

Previously known as Push & Pull.

**RZilla is an FTP, SFTP and WebDAV extension for Visual Studio Code and JetBrains IDEs.** Upload, download, sync folders and compare local and remote files directly from your desktop IDE. Powered by rclone, with support for other cloud storage services.

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

RZilla uses rclone for the actual file transfers. You only need to describe your server once in an `rclone.conf` file inside your project.

## 1. Install rclone

Install rclone yourself using your preferred method. Both the VS Code and JetBrains extensions run `rclone` from your system's `PATH`. You manage its installation and updates.

The [official installation guide](https://rclone.org/install/) documents these options:

### Windows

```powershell
winget install Rclone.Rclone
```

Alternatively, download the appropriate [official ZIP](https://rclone.org/downloads/), extract `rclone.exe`, and add its folder to your `PATH`.

### macOS

With Homebrew installed:

```sh
brew install rclone
```

For manual installation, follow the [official macOS instructions](https://rclone.org/install/#macos-installation) and place the executable in your `PATH`.

### Linux

Run the official installation script:

```sh
sudo -v ; curl https://rclone.org/install.sh | sudo bash
```

You can also install a binary or package for your system from the [official downloads](https://rclone.org/downloads/).

After installing rclone, fully close and restart VS Code or your JetBrains IDE so it receives the updated `PATH` and can detect rclone. Run `rclone version` in the environment where the extension runs to confirm availability. For cloud or remote IDEs, install rclone in that remote environment.

If rclone is unavailable, transfers and password generation show: `rclone not found. Please install rclone first on your system.`

## 2. Configure your project

### Official Method: `rclone config`

After installing rclone, open the integrated terminal in VS Code or your JetBrains IDE. **Make sure the terminal's current directory is your project root.** A terminal may start in the project folder, but an existing terminal, a custom setting or a workspace with multiple folders may use another directory. Use `pwd` (PowerShell, macOS or Linux) or `cd` without arguments (Windows Command Prompt) to display the current directory, and change to your project root if needed.

Run:

```sh
rclone config --config ./rclone.conf
```

The relative path `./rclone.conf` refers to the terminal's current directory. The command opens rclone's interactive configuration menu and saves your choices in that file. If it already exists, its connections appear in the menu; you can edit them or quit without changes.

1. Choose `n` to create a remote and name it `my-server` (or another name you prefer).
2. Select your storage type, such as FTP, SFTP, WebDAV, Google Drive or S3, and follow the authentication prompts. For services such as Google Drive, follow rclone's browser authorization instructions when prompted.
3. Create another remote named **`my-project`**, select **`alias`** as its storage type, and set its `remote` destination to your connection and target folder, for example `my-server:/httpdocs` for FTP, `my-server:Deploy` for Google Drive, or `my-server:my-bucket/deploy` for S3.
4. Save the configuration and quit the menu. RZilla uses `my-project:` as the destination for Push, Pull, Compare and Sync.

Use the same `--config ./rclone.conf` argument whenever you edit these project connections. You can also supply an absolute path if your terminal is in another directory. See the [official rclone configuration guide](https://rclone.org/docs/#configure).

Rclone handles passwords and authentication tokens during setup. The optional RZilla `pass-visible` field described below is not needed when you configure credentials through this wizard.

### Manual File Creation (Unofficial Alternative)

If you do not have an `rclone.conf` file yet, use the official command above to create it, or create it manually in your project root. For an FTP connection, start with:

```ini
[my-server]
type = ftp
host = ftp.example.com
user = my-username
pass-visible = my-password

[my-project]
type = alias
remote = my-server:/httpdocs
```

Replace the host, username, password and destination folder with your server details. For other connection types, use `rclone config` to get the appropriate settings.

The optional `pass-visible` field is supported by RZilla in both VS Code and JetBrains. When preparing a transfer, RZilla uses `rclone obscure` to create or update `pass` from this value.

After the first successful run creates `pass`, you can remove `pass-visible` and keep the generated `pass` line. RZilla never removes `pass-visible` automatically. Without it, the existing `pass` is used unchanged. You can also supply an existing rclone `pass` directly.

`pass-visible` contains your password as plain text. Keep `rclone.conf` private.

### Keep Your Configuration Private

Add `rclone.conf` to your `.gitignore` so passwords, access tokens, and server details are not committed to your repository:

```gitignore
rclone.conf
```

### The `my-project` Destination

RZilla sends project files to a destination named `my-project:`.

The `my-project` alias in the example points to the folder where your project should be deployed:

```ini
[my-project]
type = alias
remote = my-server:/httpdocs
```

Replace `my-server:/httpdocs` with your configured connection and destination folder. For example, `images/logo.png` uploads to `/httpdocs/images/logo.png` with this alias.

Transfers preserve project-relative paths. They run when you choose Push or Pull and do not delete extra files at the destination.

You can choose any name for the real server connection, but the deploy alias must be named `my-project`.

### Other Connection Types

Configure FTP, SFTP, WebDAV, Google Drive, S3, OneDrive or another [rclone storage backend](https://rclone.org/overview/) with the same wizard. Authentication and connection options vary by service; the `my-project` alias still points to your chosen destination.

## 3. Use RZilla

To upload a file or folder:

1. Select it in the editor or project explorer.
2. Choose `Push (Upload)` or `Push Folder (Upload)`.
3. Follow the live progress notification.

To download a file or folder:

1. Select its local location in the project.
2. Choose `Pull (Download)` or `Pull Folder (Download)`.
3. RZilla downloads the matching path from `my-project:`.

### Keyboard Shortcuts

- **Ctrl+Up**: upload the current file.
- **Ctrl+Down**: download the current file.
- On macOS, use **Cmd+Up** and **Cmd+Down**.

Folder transfers and multiple selections are available from the file or project context menu.

### Read-Only Website Downloads

To download and compare files directly from a website, create `.skia/read-only-url.txt` with the website URL:

```text
https://example.com
```

This downloads the public content served at each URL for file downloads and comparisons, not server-side source code such as PHP. Uploads still use rclone, and folder downloads are unavailable.

## Syncing Subfolders

In VS Code or JetBrains IDEs, right-click a project subfolder and choose **Sync Folder (Bsyncs)** below Push and Pull. In JetBrains, it is also available under **Tools → RZilla**. It runs `rclone bisync` directly for that subfolder and its matching server path. The project root and individual files cannot be synced with this command. Read-only website mode is not supported.

**Sync propagates deletions in both directions.** It starts when selected, without a confirmation step, preview or automatic backup. Closing its notification does not cancel the transfer.

On the first run, `--resync-mode newer` initializes the history and combines files from both sides. Later runs propagate changes and deletions based on that history. If both copies changed, `--conflict-resolve newer` prefers the newer modification time. This is change tracking: a change on just one side can propagate even if its timestamp became older. Unresolved conflicts keep both versions using rclone's numbered conflict names.

Deleting a server file can therefore delete the unchanged local copy, and deleting a local file can delete its server copy. If the surviving copy changed, rclone normally preserves it and copies it back. Sync needs reliable server modification times. Equal dates and sizes can conceal content changes, especially on FTP servers without checksums.

Rclone keeps its history in the extension's private `bisync` storage for each project and subfolder. Use the same selected folders on subsequent runs and avoid overlapping parent/child syncs. Existing history is never automatically reset after an error; rclone reports when manual recovery is needed. Its built-in locks and deletion limits remain enabled.

VS Code and JetBrains keep separate sync histories. Use one editor for a given folder's ongoing Bsyncs sessions.

`rclone.conf` is always excluded from Sync and folder transfers; direct Push/Pull of that file is blocked. It is read locally to configure the connection.

## Comparing Online and Local Files

Open a local file and choose `Compare Online with Local`.

RZilla downloads the matching online file to the editor's private storage and opens the built-in diff viewer:

- `Online` appears on the left.
- `Local` appears on the right.
- Unsaved changes in the current editor are included.
- Your local file is not overwritten.

## Finding the Project Configuration

RZilla normally uses the `rclone.conf` in the project root.

If the selected file belongs to a nested project, RZilla searches its parent folders for the nearest `rclone.conf`. This allows different projects to use different servers and deployment folders.

## Transfer Settings

Open your editor settings and search for `RZilla`.

Available settings:

- `Transfers`: how many files rclone can transfer at the same time. The default is `2`.
- `Checkers`: how many checks rclone can run at the same time. The default is `4`.

The default values are suitable for most servers. Higher values may make transfers faster on powerful servers, while lower values may work better on limited shared hosting.

## Transfer Progress and Errors

During an upload or download, RZilla shows the latest transfer information, including transferred size, percentage, speed, and estimated time when rclone provides them.

When the transfer finishes, you receive a completion notification. If it fails, the notification shows the latest useful error returned by rclone.

## Bug Reports and Feedback

For bug reports, questions and feedback, open an issue on [GitHub Issues](https://github.com/libandreas/push-and-pull/issues).

## License

RZilla is licensed under the [GNU General Public License](LICENSE), version 3 or any later version (GPL-3.0-or-later).

## Contributing

Help improve RZilla on [GitHub](https://github.com/libandreas/push-and-pull). Report bugs, suggest features, improve the documentation or submit a pull request. Contributions for both the VS Code extension and the JetBrains plugin are welcome.
