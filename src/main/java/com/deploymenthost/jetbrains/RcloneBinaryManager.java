package com.deploymenthost.jetbrains;

import java.io.IOException;
import java.nio.file.Path;

final class RcloneBinaryManager {
    Path ensureRcloneAvailable() throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("rclone", "version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException error) {
            if (error.getMessage().contains("error=2,")) {
                throw new IOException("rclone not found. Please install rclone first on your system.", error);
            }
            throw error;
        }
        if (process.waitFor() != 0) {
            throw new IOException("Could not run rclone version. Check your system installation.");
        }
        return Path.of("rclone");
    }
}
