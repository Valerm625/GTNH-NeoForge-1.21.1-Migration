package org.gtnhmigration.audit.launcher;

import net.neoforged.fml.loading.targets.NeoForgeServerLaunchHandler;

/** Production dedicated-server distribution, with a registry-only test entry point. */
public final class StartupAuditLaunchHandler extends NeoForgeServerLaunchHandler {
    @Override public String name() { return "gtnhserverregistryaudit"; }
    @Override public void runService(String[] args, ModuleLayer layer) throws Throwable {
        Module module = layer.findModule("gtnh_startup_audit").orElseThrow();
        Class.forName(module, "org.gtnhmigration.startupaudit.StartupAuditMain")
            .getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
