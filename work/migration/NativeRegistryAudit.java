package org.gtnhmigration.audit;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.gtnhmigration.compat.*;

/** Test-only mod. Reads actual registries after NeoForge registration, without a world. */
@Mod("gtnh_registry_audit")
public final class NativeRegistryAudit {
    public NativeRegistryAudit(IEventBus bus) { bus.addListener(this::gather); }

    private void gather(GatherDataEvent event) {
        try {
            JsonObject report = new JsonObject();
            report.addProperty("test", "native_neoforge_datagen_registry");
            JsonArray mods = new JsonArray();
            ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId()))
                .forEach(m -> { JsonObject row = new JsonObject(); row.addProperty("id", m.getModId());
                    row.addProperty("version", m.getVersion().toString()); mods.add(row); });
            report.add("mods", mods);
            JsonObject registries = new JsonObject();
            registries.add("items", keys(BuiltInRegistries.ITEM));
            registries.add("blocks", keys(BuiltInRegistries.BLOCK));
            registries.add("fluids", keys(BuiltInRegistries.FLUID));
            registries.add("entities", keys(BuiltInRegistries.ENTITY_TYPE));
            report.add("registries", registries);

            // Defining these classes exercises the real ModLauncher transformer chain.
            // No server, player, inventory, or world is instantiated.
            JsonArray defined = new JsonArray();
            String[] targets = {
                "net.minecraft.world.entity.Entity", "net.minecraft.server.MinecraftServer",
                "net.minecraft.server.players.PlayerList", "net.minecraft.world.level.storage.PlayerDataStorage",
                "net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess",
                "net.minecraft.server.level.ServerPlayer", "net.minecraft.client.server.IntegratedPlayerList",
                "net.minecraft.world.inventory.AbstractContainerMenu",
                "dev.ftb.mods.ftbquests.quest.task.ItemTask", "dev.ftb.mods.ftbquests.quest.task.KillTask",
                "dev.ftb.mods.ftbquests.quest.task.LocationTask", "dev.ftb.mods.ftbquests.quest.task.Task",
                "dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener",
                "codechicken.enderstorage.storage.EnderLiquidStorage",
                "org.gtnhmigration.compat.LegacyQuestNativeLifecycle",
                "org.gtnhmigration.compat.LegacyRetrievalNativeSaveScope"
            };
            for (String target : targets) {
                Class.forName(target, false, getClass().getClassLoader()); defined.add(target);
            }
            report.add("defined_classes", defined);
            String[] hooks = {"PlayerDataLoadHooks", "ServerAccess", "PlayerListHooks", "PlayerFileHooks",
                "WorldFileHooks", "PlayerLifecycleHooks", "OwnerCacheHooks"};
            for (int i = 0; i < hooks.length; i++) {
                Class<?> hook = Class.forName("org.gtnhmigration.compat.LegacyRetrievalNativeSaveScope$"+hooks[i]);
                if (!hook.isAssignableFrom(Class.forName(targets[i], false, getClass().getClassLoader())))
                    throw new IllegalStateException("Missing applied native Mixin: "+hooks[i]);
            }
            report.addProperty("native_hook_interfaces_verified", hooks.length);
            int capacity = Class.forName("codechicken.enderstorage.storage.EnderLiquidStorage")
                .getField("CAPACITY").getInt(null);
            if (capacity != 256_000) throw new IllegalStateException("EnderStorage capacity differs: "+capacity);
            report.addProperty("ender_storage_capacity_mb", capacity);
            boolean datagen = dev.latvian.mods.kubejs.script.PlatformWrapper.isGeneratingData();
            if (!datagen) throw new IllegalStateException("Expected KubeJS data generation mode");
            report.addProperty("kubejs_data_generation_mode", datagen);
            // Datagen uses the CLIENT distribution: the original rendering bodies
            // must remain available, and each lamp state must retain its renderer.
            Class<?> fancy = Class.forName("com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine");
            Class<?> group = Class.forName("com.lowdragmc.lowdraglib.gui.widget.WidgetGroup");
            fancy.getDeclaredMethod("gtnhMigration$addClientScene", group);
            Class<?> lamp = Class.forName("com.gregtechceu.gtceu.common.block.LampBlock");
            lamp.getDeclaredMethod("gtnhMigration$initializeRenderers");
            var renderers = lamp.getDeclaredField("renderers"); renderers.setAccessible(true);
            int lampCount = 0, rendererCount = 0;
            for (var block : BuiltInRegistries.BLOCK) {
                if (!lamp.isInstance(block)) continue;
                lampCount++;
                Map<?,?> byState = (Map<?,?>) renderers.get(block);
                if (byState.size() != block.getStateDefinition().getPossibleStates().size())
                    throw new IllegalStateException("Missing client lamp renderer");
                for (var state : block.getStateDefinition().getPossibleStates())
                    if (byState.get(state) == null || !byState.get(state).getClass().getName().equals("com.gregtechceu.gtceu.client.renderer.block.LampRenderer"))
                        throw new IllegalStateException("Replaced client lamp renderer");
                rendererCount += byState.size();
            }
            if (lampCount != 32 || rendererCount != 512) throw new IllegalStateException("Lamp variants differ");
            report.addProperty("client_helpers_retained", 2);
            report.addProperty("client_lamp_renderers_constructed", rendererCount);
            report.add("library_client_callbacks", LibraryClientCallbacksCheck.verifyRuntime(true, Path.of("library-bytecode-check.json")));
            LegacyQuestDefinitionArchive archive = LegacyQuestDefinitionArchive.loadPinned(Path.of("."));
            report.addProperty("archive_sha256", archive.pin().archiveSha256());
            JsonArray mappings = new JsonArray();
            archive.mappedIdentities().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().legacyId()+":"+e.getKey().damage()))
                .forEach(e -> {
                    JsonObject row = new JsonObject(); row.addProperty("legacy_id", e.getKey().legacyId());
                    row.addProperty("damage", e.getKey().damage()); row.addProperty("target", e.getValue());
                    row.addProperty("registered", BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(e.getValue())));
                    mappings.add(row);
                });
            report.add("identity_mappings", mappings);
            JsonArray bindings = new JsonArray();
            var lookup = event.getLookupProvider().join();
            for (UUID id : archive.questIds()) {
                var result = LegacyRetrievalDefinitionBinding.inspect(archive, id, lookup);
                JsonObject row = new JsonObject(); row.addProperty("quest_uuid", id.toString());
                row.addProperty("source", archive.questPath(id)); row.addProperty("bound", result.bound().isPresent());
                row.add("problems", new Gson().toJsonTree(result.problems())); bindings.add(row);
            }
            report.add("retrieval_bindings", bindings);
            report.addProperty("passed", true);
            report.addProperty("world_or_player_executed", false);
            Files.writeString(Path.of("native-registry-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("GTNH_NATIVE_REGISTRY_AUDIT_COMPLETE");
        } catch (Exception error) { throw new IllegalStateException("Native registry audit failed", error); }
    }

    private static JsonArray keys(Registry<?> registry) {
        JsonArray keys = new JsonArray();
        registry.keySet().stream().map(Object::toString).sorted().forEach(keys::add);
        return keys;
    }
}
