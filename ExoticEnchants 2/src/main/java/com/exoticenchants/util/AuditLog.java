package com.exoticenchants.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.logging.Logger;

/** Append-only audit.log for claims, transfers, removals and admin actions. */
public final class AuditLog {

    private final File file;
    private final Logger logger;

    public AuditLog(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "audit.log");
        this.logger = logger;
    }

    public synchronized void log(String line) {
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println(Instant.now() + " " + line);
        } catch (IOException ex) {
            logger.warning("Could not write audit.log: " + ex.getMessage());
        }
    }
}
