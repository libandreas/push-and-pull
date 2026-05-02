# Push & Pull

Push & Pull is a deploy interface for VS Code.

It keeps the workflow simple: push files up, pull files down, and deploy without leaving the editor. Use `Ctrl+Up` to push/upload the current file and `Ctrl+Down` to pull/download it. On macOS, use `Cmd+Up` and `Cmd+Down`.

It is meant to feel quick and practical inside VS Code, like a tiny deploy cockpit for the files you are already editing.

## Supported Protocols

Push & Pull can work with many production-friendly storage and deploy targets, including:

- FTP
- SFTP
- FTPS
- SSH
- WebDAV
- S3
- S3-compatible storage
- SMB
- Swift
- Azure Blob Storage
- Google Cloud Storage
- Backblaze B2
- Many others

## VS Code Actions

For files:

- `Push (Upload)`
- `Pull (Download)`

For folders in the Explorer:

- `Push Folder (Upload)`
- `Pull Folder (Download)`

Editor shortcuts:

- `Ctrl+Up`: push/upload the current file
- `Ctrl+Down`: pull/download the current file
- macOS uses `Cmd+Up` and `Cmd+Down`

The extension sends deploy commands into a VS Code terminal named `Push & Pull`.

## VS Code Settings

Open VS Code Settings and search for `Push & Pull`.

Available settings:

- `Push Pull: Transfers`: rclone `--transfers`, default `4`
- `Push Pull: Checkers`: rclone `--checkers`, default `8`

These control how many transfers and checks rclone runs in parallel. They work across deploy protocols such as FTP, SFTP, SSH, WebDAV, S3, and others, but the best value depends on the server. The defaults are conservative for shared hosting. If your backend is fast and stable, you can raise the numbers.

## Config and Setup

Put `rclone.conf` in the project root.

Do not forget to add `rclone.conf` to your `.gitignore`, especially if it contains passwords, tokens, or `pass-visible`.

Create a config file for your server in `rclone.conf`. The name inside brackets is your remote name. Users should choose their own names. In this example, the server remote is called `[my-server]`:

```ini
[my-server]
type = ftp
host = example.com
user = my-user
port = 21
explicit_tls = true
passive = true
no_check_certificate = true
pass-visible = my-password
pass = generated-by-rclone
```

If your website files live inside a deploy folder, you can also create an alias that points directly there. Use your own project name here too:

```ini
[my-project]
type = alias
remote = your-real-remote:/httpdocs
```

For example:

```ini
[my-project]
type = alias
remote = my-server:/httpdocs
```

`[my-project]` is only an example. You can call it `[deploy]`, `[website]`, `[client-site]`, or any name that makes sense for your project.

Push & Pull always uses `my-project:` as the project deploy remote, so create a `[my-project]` alias in `rclone.conf`.

Then the extension can use clean paths like:

```text
my-project:/test/1.html
```

instead of requiring the deploy folder in every command.

## Password Handling

If `rclone.conf` contains:

```ini
pass-visible = your-password
```

Push & Pull silently runs:

```text
rclone obscure
```

through an internal process before every push or pull action. It then writes the generated rclone password under `pass-visible`:

```ini
pass-visible = your-password
pass = generated-by-rclone
```

It does not remove `pass-visible`.

## Command Examples

Upload a file:

```powershell
rclone --config .\rclone.conf copy .\test-1.html my-project: --progress
```

Download a file:

```powershell
rclone --config .\rclone.conf copy my-project:/test-1.html . --progress
```

Upload a folder:

```powershell
rclone --config .\rclone.conf copy .\test my-project:/test --progress
```

Download a folder:

```powershell
rclone --config .\rclone.conf copy my-project:/test .\test --progress
```

## Requirement

As you can see, Push & Pull runs `rclone` as a backend for uploading and downloading files. You must install `rclone` on your computer and make sure it is available in your terminal PATH.

Windows:

```powershell
winget install Rclone.Rclone
```

macOS with Homebrew:

```bash
brew install rclone
```

Linux, macOS, or BSD with the official install script:

```bash
sudo -v ; curl https://rclone.org/install.sh | sudo bash
```

Manual download:

```text
https://rclone.org/downloads/
```

After installing, restart VS Code or open a new terminal and check:

```bash
rclone version
```

## Contributing

This section is for people who want to help with the programming of Push & Pull.

The main idea is intentionally simple: Push & Pull does not implement its own deploy logic. We do not crawl directories, compare files, upload chunks, or re-create sync behavior ourselves. That work belongs to `rclone`.

Push & Pull is the IDE layer around that workflow. It adds icons, context menu actions, shortcuts, and settings inside the editor, then runs `rclone` with the project's `rclone.conf` so files and folders can be pushed and pulled from the IDE.

The extension backend should stay small:

- handle `pass-visible` by generating the correct `pass` value with `rclone obscure`
- build the correct `rclone copy` command for the selected file or folder
- open the IDE terminal
- send the command to the terminal so the user can see and control what runs

Everything after that should be handled by `rclone`.

The VS Code extension is the active implementation. The JetBrains plugin files are currently in construction and have not been tested yet with `runIde` or a packaged plugin build.