package org.gtnhmigration.startupaudit;

import com.google.gson.*;
import com.gregtechceu.gtceu.data.material.GTMaterials;
import com.gregtechceu.gtceu.api.material.material.info.MaterialFlags;
import com.gregtechceu.gtceu.common.block.LampBlock;
import dev.latvian.mods.kubejs.script.PlatformWrapper;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.internal.CommonModLoader;
import net.neoforged.neoforge.internal.RegistrationEvents;
import org.gtnhmigration.compat.*;

/** Executes real mod constructors and registration. Never constructs a server/world/player. */
@Mod("gtnh_startup_audit")
public final class StartupAuditMain extends CommonModLoader {
    public StartupAuditMain() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            require(!PlatformWrapper.isGeneratingData(), "KubeJS must use its ordinary startup path");
            begin(() -> {}, false);
            RegistrationEvents.modifyComponents();
            JsonObject report = new JsonObject();
            report.addProperty("test", "native_neoforge_startup_registration");
            report.addProperty("kubejs_data_generation_mode", false);
            JsonObject registries = new JsonObject();
            registries.addProperty("items", BuiltInRegistries.ITEM.size());
            registries.addProperty("blocks", BuiltInRegistries.BLOCK.size());
            registries.addProperty("fluids", BuiltInRegistries.FLUID.size());
            registries.addProperty("entities", BuiltInRegistries.ENTITY_TYPE.size());
            report.add("registries", registries);
            JsonArray custom = new JsonArray();
            BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals("gtnh_migration"))
                .map(Object::toString).sorted().forEach(custom::add);
            report.add("custom_items", custom);
            JsonArray mods = new JsonArray();
            ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId()))
                .forEach(m -> { JsonObject row = new JsonObject(); row.addProperty("id", m.getModId());
                    row.addProperty("version", m.getVersion().toString()); mods.add(row); });
            report.add("mods", mods);

            LegacyQuestDefinitionArchive archive = LegacyQuestDefinitionArchive.loadPinned(Path.of("."));
            report.addProperty("archive_sha256", archive.pin().archiveSha256());
            JsonArray mappings = new JsonArray();
            for (var e : archive.mappedIdentities().entrySet()) {
                JsonObject row = new JsonObject(); row.addProperty("legacy_id", e.getKey().legacyId());
                row.addProperty("damage", e.getKey().damage()); row.addProperty("target", e.getValue());
                boolean exists = BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(e.getValue()));
                row.addProperty("registered", exists); mappings.add(row);
                require(exists, "Missing mapped item " + e.getValue());
            }
            report.add("identity_mappings", mappings);
            JsonArray bindings = new JsonArray();
            var lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            for (UUID id : archive.questIds()) {
                var result = LegacyRetrievalDefinitionBinding.inspect(archive, id, lookup);
                JsonObject row = new JsonObject(); row.addProperty("quest_uuid", id.toString());
                row.addProperty("source", archive.questPath(id)); row.addProperty("bound", result.bound().isPresent());
                row.add("problems", new Gson().toJsonTree(result.problems())); bindings.add(row);
            }
            report.add("retrieval_bindings", bindings);

            var alloy = GTMaterials.SolderingAlloy;
            List<String> parts = new ArrayList<>();
            for (var part : alloy.getMaterialComponents()) parts.add(part.material().getName()+":"+part.amount());
            require(parts.equals(List.of("tin:9", "antimony:1")), "Incorrect alloy components: " + parts);
            require(alloy.hasFlag(MaterialFlags.DISABLE_DECOMPOSITION), "Generic decomposition still enabled");
            report.add("solder_components", new Gson().toJsonTree(parts));
            report.addProperty("solder_generic_decomposition_disabled", true);

            // KubeJS 2101 stores fuel in generated NeoForge data maps. Before a
            // resource reload, ItemStack.getBurnTime is legitimately zero.
            // Exercise the actual builtin generator and codec; do not inject a
            // handcrafted map into the registry to manufacture a burn result.
            var dataPack = new dev.latvian.mods.kubejs.script.data.VirtualDataPack(
                dev.latvian.mods.kubejs.script.data.GeneratedDataStage.INTERNAL,
                () -> new dev.latvian.mods.kubejs.util.RegistryAccessContainer(lookup.freeze()));
            new dev.latvian.mods.kubejs.plugin.builtin.BuiltinKubeJSPlugin().generateData(dataPack);
            dataPack.flush();
            var fuelData = dataPack.getGenerated(ResourceLocation.parse("neoforge:data_maps/item/furnace_fuels.json"));
            require(fuelData != null, "No generated furnace fuel data map");
            var fuelJson = JsonParser.parseString(new String(fuelData.data().get(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            report.add("generated_furnace_fuel_data_map", fuelJson);
            JsonArray fuels = new JsonArray();
            JsonArray spec = JsonParser.parseString(Files.readString(Path.of("fuel-spec.json")))
                .getAsJsonObject().getAsJsonArray("blocks");
            for (var entry : spec) {
                var expected = entry.getAsJsonObject();
                var id = ResourceLocation.parse(expected.get("target_id").getAsString());
                require(BuiltInRegistries.BLOCK.containsKey(id), "Missing fuel block " + id);
                var block = BuiltInRegistries.BLOCK.get(id); var state = block.defaultBlockState();
                var stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                var encodedFuel = fuelJson.getAsJsonObject("values").get(id.toString());
                int burn = net.neoforged.neoforge.registries.datamaps.builtin.FurnaceFuel.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, encodedFuel).getOrThrow().burnTime();
                require(burn == expected.get("burn_ticks").getAsInt(), "Fuel duration differs " + id + ": " + burn);
                require(stack.getMaxStackSize() == 64, "Fuel stack size " + id);
                require(state.getDestroySpeed(null, BlockPos.ZERO) == expected.get("hardness").getAsFloat(), "Hardness " + id);
                require(block.getExplosionResistance() == expected.get("explosion_resistance").getAsFloat(), "Resistance " + id);
                require(state.requiresCorrectToolForDrops(), "Harvest requirement " + id);
                JsonObject row = new JsonObject(); row.addProperty("id", id.toString()); row.addProperty("burn_ticks", burn);
                row.addProperty("pre_resource_reload_burn_ticks", stack.getBurnTime(null));
                fuels.add(row);
            }
            report.add("fuel_blocks", fuels);
            int lamps = 0;
            for (var block : BuiltInRegistries.BLOCK) {
                if (!(block instanceof LampBlock)) continue;
                lamps++;
                require(block.getStateDefinition().getPossibleStates().size() == 16, "Lamp state variants lost");
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    var lamp = (LampBlock) block;
                    require(lamp.getDataFromState(state) != null, "Lamp item data lost");
                }
            }
            require(lamps > 0, "Lamps omitted");
            report.addProperty("registered_lamps", lamps);
            // Both helpers must be stripped by the real dedicated-server dist cleaner.
            for (String name : List.of("com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine", "com.gregtechceu.gtceu.common.block.LampBlock"))
                for (var method : Class.forName(name).getDeclaredMethods())
                    require(!method.getName().startsWith("gtnhMigration$"), "Client helper present on server");
            report.addProperty("client_helpers_stripped_on_server", true);
            report.add("library_client_callbacks", org.gtnhmigration.audit.LibraryClientCallbacksCheck.verifyRuntime(false, Path.of("library-bytecode-check.json")));
            int capacity = Class.forName("codechicken.enderstorage.storage.EnderLiquidStorage").getField("CAPACITY").getInt(null);
            require(capacity == 256_000, "EnderStorage capacity");
            report.addProperty("ender_storage_capacity_mb", capacity);
            report.addProperty("passed", true);
            report.addProperty("world_or_player_executed", false);
            report.addProperty("recipes_resources_or_rendering_executed", false);
            report.addProperty("builtin_furnace_data_generation_executed", true);
            Files.writeString(Path.of("native-startup-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("GTNH_NATIVE_STARTUP_AUDIT_COMPLETE");
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
