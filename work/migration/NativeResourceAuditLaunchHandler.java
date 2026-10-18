package org.gtnhmigration.audit.launcher;

import net.neoforged.fml.loading.targets.NeoForgeServerLaunchHandler;

/** Mod/library/resource loading only. Never enters Minecraft's server Main. */
public final class NativeResourceAuditLaunchHandler extends NeoForgeServerLaunchHandler {
    @Override public String name() { return "gtnhresourceaudit"; }
    @Override public void runService(String[] args, ModuleLayer layer) throws Throwable {
        Module module = layer.findModule("gtnh_startup_audit").orElseThrow();
        Class.forName(module, "org.gtnhmigration.startupaudit.NativeResourceAuditMain")
            .getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
