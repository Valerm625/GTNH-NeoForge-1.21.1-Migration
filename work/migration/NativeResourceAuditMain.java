package org.gtnhmigration.startupaudit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.loading.ServerModLoader;
import net.neoforged.neoforge.internal.RegistrationEvents;
@Mod("gtnh_startup_audit")
public final class NativeResourceAuditMain {
 public NativeResourceAuditMain(){}
 public static void main(String[] args) {try {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  ServerModLoader.load(); RegistrationEvents.modifyComponents();
  if (ServerModLoader.hasErrors()) throw new IllegalStateException("Mod initialization failed");
  System.out.println("GTNH_FULL_MOD_INITIALIZATION_COMPLETE_NO_WORLD");
  java.util.concurrent.atomic.AtomicInteger reloadEvents = new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicInteger tagEvents = new java.util.concurrent.atomic.AtomicInteger();
  net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddReloadListenerEvent event) -> reloadEvents.incrementAndGet());
  net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.TagsUpdatedEvent event) -> {
      if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) tagEvents.incrementAndGet();
  });
  var repository = net.minecraft.server.packs.repository.ServerPacksSource.createPackRepository(
      java.nio.file.Path.of("datapacks"), new net.minecraft.world.level.validation.DirectoryValidator(path -> false));
  var config = new net.minecraft.server.WorldLoader.InitConfig(
      new net.minecraft.server.WorldLoader.PackConfig(repository, net.minecraft.world.level.WorldDataConfiguration.DEFAULT, false, true),
      net.minecraft.commands.Commands.CommandSelection.DEDICATED, 2);
  var result = net.minecraft.server.WorldLoader.load(config,
      context -> new net.minecraft.server.WorldLoader.DataLoadOutput<>("audit", context.datapackDimensions()),
      (resources, data, registries, cookie) -> {
          // WorldLoader already updates tags before invoking this result factory.
          System.out.println("GTNH_REAL_RESOURCE_RELOAD_RECIPES="+data.getRecipeManager().getRecipes().size());
          System.out.println("GTNH_REAL_RESOURCE_RELOAD_FUEL="+new net.minecraft.world.item.ItemStack(
              net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("gtnh_migration:charcoal"))).getBurnTime(null));
          if (reloadEvents.get() != 1 || tagEvents.get() != 1) throw new IllegalStateException("Unexpected resource lifecycle event counts");
          if (net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() != null) throw new IllegalStateException("Unexpected server instance");
          var dump = new com.google.gson.JsonObject();
          dump.addProperty("reload_listener_events", reloadEvents.get());
          dump.addProperty("tag_update_events", tagEvents.get());
          dump.addProperty("server_instance_present", false);
          var recipes = new com.google.gson.JsonObject();
          var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries.compositeAccess());
          for (var holder : data.getRecipeManager().getRecipes()) {
              if (holder.id().getNamespace().equals("gtnh_migration") || holder.id().getNamespace().equals("gtnh_audit")) {
                  recipes.add(holder.id().toString(), net.minecraft.world.item.crafting.Recipe.CODEC.encodeStart(ops, holder.value()).getOrThrow());
              }
          }
          dump.add("recipes", recipes);
          try { dump.add("shaped_behavior", NativeShapedRecipeCheck.run(data.getRecipeManager(), ops, registries.compositeAccess())); }
          catch (Exception error) { throw new RuntimeException(error); }
            dump.add("legacy_crafting_energy", NativeCraftingEnergyCheck.run(data.getRecipeManager(), ops, registries.compositeAccess()));
          try { dump.add("legacy_crafting_nbt", NativeCraftingNbtCheck.run(data.getRecipeManager(), ops, registries.compositeAccess())); }
          catch (Exception error) { throw new RuntimeException(error); }
          try { dump.add("legacy_shapeless", NativeShapelessRecipeCheck.run(data.getRecipeManager(), ops, registries.compositeAccess())); }
          catch (Exception error) { throw new RuntimeException(error); }
          var storage = ((dev.latvian.mods.kubejs.core.ReloadableServerResourcesKJS) data).kjs$getServerScriptManager().recipeSchemaStorage;
          var componentCodec = storage.recipeComponentCodec;
          var components = new com.google.gson.JsonArray();
          for (var component : java.util.List.of(
              com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.ITEM,
              com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.FLUID,
              com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.EU,
              com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.CWU,
              com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.CHANCE_LOGIC_MAP)) {
              var encoded = componentCodec.encodeStart(ops, component).getOrThrow();
              var decoded = componentCodec.parse(ops, encoded).getOrThrow();
              var second = componentCodec.encodeStart(ops, decoded).getOrThrow();
              if (!encoded.equals(second) || !component.getClass().equals(decoded.getClass())) throw new IllegalStateException("Recipe component round trip changed " + encoded);
              components.add(encoded);
          }
          dump.add("component_round_trips", components);
          // Copies, codecs and network payloads carry normalized boosts. Re-normalizing
          // the 6000 denominator changes 450 to 270 and loses recipe behavior.
          int boostRoundTrips = 0;
          var itemCapability = com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability.CAP;
          var itemContentCodec = com.gregtechceu.gtceu.api.recipe.content.Content.codec(itemCapability);
          for (int maximum : new int[]{6000, 9999, 10000}) {
              for (int boost : new int[]{750, -750}) {
                  var value = new com.gregtechceu.gtceu.api.recipe.content.Content(
                      net.neoforged.neoforge.common.crafting.SizedIngredient.of(net.minecraft.world.item.Items.DIAMOND, 2), 1, maximum, boost);
                  int expected = Math.round(Math.abs(boost) / (10000.0f / maximum)) * Integer.signum(boost);
                  var copied = value.copy(itemCapability);
                  var encoded = itemContentCodec.encodeStart(ops, value).getOrThrow();
                  var decoded = itemContentCodec.parse(ops, encoded).getOrThrow();
                  var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries.compositeAccess(), net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
                  try {
                      itemCapability.serializer.toNetworkContent(buffer, value);
                      var network = itemCapability.serializer.fromNetworkContent(buffer);
                      if (value.tierChanceBoost != expected || copied.tierChanceBoost != expected || decoded.tierChanceBoost != expected || network.tierChanceBoost != expected) throw new IllegalStateException("Chance boost changed across copy/codec/network");
                      if (!encoded.equals(itemContentCodec.encodeStart(ops, copied).getOrThrow()) || !encoded.equals(itemContentCodec.encodeStart(ops, decoded).getOrThrow()) || !encoded.equals(itemContentCodec.encodeStart(ops, network).getOrThrow())) throw new IllegalStateException("Content changed across copy/codec/network");
                      boostRoundTrips += 3;
                  } finally { buffer.release(); }
              }
          }
          dump.addProperty("boost_round_trips", boostRoundTrips);
          var fuels = new com.google.gson.JsonObject();
          for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
              var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
              if (id.getNamespace().equals("gtnh_migration")) {
                  int ticks = new net.minecraft.world.item.ItemStack(item).getBurnTime(null);
                  if (ticks > 0) fuels.addProperty(id.toString(), ticks);
              }
          }
          dump.add("fuels", fuels);
          try { java.nio.file.Files.writeString(java.nio.file.Path.of("resource-dump.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(dump)); }
          catch (java.io.IOException error) { throw new RuntimeException(error); }
          resources.close(); return true;
      }, net.minecraft.Util.backgroundExecutor(), Runnable::run).get(120, java.util.concurrent.TimeUnit.SECONDS);
  if (!result) throw new IllegalStateException("Resource reload failed");
  System.out.println("GTNH_RESOURCE_PROBE_COMPLETE_NO_WORLD"); System.exit(0);
 }catch(Throwable error){error.printStackTrace();System.exit(1);}}
}
