package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

/** Installed native call-site adapter. Unbound worlds keep vanilla behavior.
 * Bound worlds retain their ledger lock from loadLevel until successful stop.
 * Native game execution and other-mod compatibility still require validation.
 */
public final class LegacyQuestNativeLifecycle {
    private static final Map<MinecraftServer,Mount> WORLDS=new IdentityHashMap<>();
    private static final ThreadLocal<Load> LOAD=new ThreadLocal<>();
    private static final class Mount {
        final LegacyQuestWorldStore world;boolean ready;
        Mount(LegacyQuestWorldStore world){this.world=world;}
    }
    private record Load(ServerPlayer player,PlayerList list,LegacyRetrievalSaveScope.Lease scope,LegacyQuestPlayerAdmission.Receipt receipt) {}
    private LegacyQuestNativeLifecycle(){}
    public static void beforeWorldLoad(MinecraftServer server){
        requireThread(server);
        synchronized(WORLDS){if(WORLDS.containsKey(server))throw new IllegalStateException("World load already initialized");}
        try(var scope=LegacyRetrievalNativeSaveScope.beginServer(server)){
            try{
                if(!hasMetadata(server)){synchronized(WORLDS){var empty=new Mount(null);empty.ready=true;WORLDS.put(server,empty);}scope.release();return;}
                requireEmpty(server);var world=LegacyQuestWorldStore.open(server.getWorldPath(LevelResource.ROOT));
                var mount=new Mount(world);synchronized(WORLDS){WORLDS.put(server,mount);}
                Runnable barrier=()->{
                    scope.assertHeld();requireThread(server);requireEmpty(server);
                };
                barrier.run();LegacyPlayerMarkerUpgrade.resume(world,()->server.getWorldData().getLoadedPlayerTag(),barrier);
                LegacyOwnerTransactionRecovery.recover(world,()->server.getWorldData().getLoadedPlayerTag(),barrier);
                LegacyQuestPlayerAdmission.verifyPendingWorldCache(world,server.getWorldData().getLoadedPlayerTag());
                world.recoverOffline(barrier);
                LegacyQuestPlayerAdmission.verifyResolvedWorldCache(world,server.getWorldData().getLoadedPlayerTag());
                CompoundTag cached=server.getWorldData().getLoadedPlayerTag();
                if(cached!=null)LegacyPlayerMarkerUpgrade.promote(world,cached.getUUID("UUID"),LegacyPlayerDataFileReader.Authority.WORLD_PLAYER,()->server.getWorldData().getLoadedPlayerTag(),barrier);
                world.verifyBinding();scope.release();mount.ready=true;
            }catch(IOException|RuntimeException|Error failure){scope.quarantine();throw failure;}
        }catch(IOException failure){throw new UncheckedIOException("Bound quest world recovery failed before world loading",failure);}
    }
    public static void afterWorldStop(MinecraftServer server){
        Mount mount;synchronized(WORLDS){mount=WORLDS.get(server);}
        if(mount==null)return;
        try{if(mount.world!=null)mount.world.close();}
        catch(IOException failure){throw new UncheckedIOException("Could not close world quest ledger",failure);}
        synchronized(WORLDS){WORLDS.remove(server);}
    }
    /** Future task handlers use this already-mounted handle, never open a second
     * ledger or create one implicitly when startup recovery did not succeed.
     */
    public static Optional<LegacyQuestWorldStore> world(MinecraftServer server){
        Mount mount;synchronized(WORLDS){mount=WORLDS.get(server);}
        if(mount==null || mount.world==null){if(hasMetadata(server))throw new IllegalStateException("Bound world has not completed startup recovery");return Optional.empty();}
        requireThread(server);if(!mount.ready)throw new IllegalStateException("Bound world remains quarantined during startup recovery");
        return Optional.of(mount.world);
    }
    public static Optional<CompoundTag> load(PlayerList list,ServerPlayer player){
        MinecraftServer server=player.server;
        LegacyRetrievalSaveScope.denyLifecycle(server,null,"nested player admission");
        var mounted=world(server);if(mounted.isEmpty())return list.load(player);requireThread(server);
        if(server.getPlayerList()!=list || list.getPlayer(player.getUUID())!=null || LOAD.get()!=null)throw new IllegalStateException("Player admission identity/reentry mismatch");
        LegacyQuestWorldStore world=mounted.orElseThrow();
        var access=(LegacyRetrievalNativeSaveScope.ServerAccess)server;
        try(var scope=LegacyRetrievalNativeSaveScope.begin(player,access.gtnhMigration$playerStorage())){
            try{synchronized(world.ledger()){
                boolean owner=server.isSingleplayerOwner(player.getGameProfile()) && server.getWorldData().getLoadedPlayerTag()!=null;
                Runnable promotionBarrier=()->{scope.assertHeld();requireThread(server);if(list.getPlayer(player.getUUID())!=null)throw new IllegalStateException("Player became active during marker promotion");};
                LegacyPlayerMarkerUpgrade.promote(world,player.getUUID(),owner?LegacyPlayerDataFileReader.Authority.WORLD_PLAYER:LegacyPlayerDataFileReader.Authority.PLAYER_FILE,()->server.getWorldData().getLoadedPlayerTag(),promotionBarrier);
                var receipt=LegacyQuestPlayerAdmission.begin(world,player.getUUID(),server.isSingleplayerOwner(player.getGameProfile()),server.getWorldData().getLoadedPlayerTag());
                LOAD.set(new Load(player,list,scope,receipt));
                var result=LegacyRetrievalSaveScope.root(server,PLAYER_LOAD,player,()->list.load(player));
                receipt.finish(result,player.getUUID(),player.getPersistentData());scope.release();return result;
            }}catch(IOException|RuntimeException|Error failure){scope.quarantine();throw failure;}
            finally{LOAD.remove();}
        }catch(IOException failure){throw new UncheckedIOException("Player admission failed; save/lifecycle quarantine retained",failure);}
    }
    public static void requireListLoad(PlayerList list,ServerPlayer player){
        if(world(player.server).isEmpty())return;
        Load load=requireLoad(player);if(load.list()!=list)throw poison(load,"Different player list during admission");
    }
    public static void requireFileLoad(Player player){
        if(player instanceof ServerPlayer serverPlayer && world(serverPlayer.server).isPresent())requireLoad(serverPlayer);
    }
    public static Optional<CompoundTag> loadFile(PlayerDataStorage storage,Player player){
        if(!(player instanceof ServerPlayer p))return storage.load(player);
        return LegacyRetrievalSaveScope.child(p.server,PLAYER_LOAD,PLAYER_FILE_LOAD,player,()->storage.load(player));
    }
    public static CompoundTag readPlayerFile(Path path,NbtAccounter accounter)throws IOException {
        Load load=LOAD.get();if(load==null)return NbtIo.readCompressed(path,accounter);
        try{return load.receipt().readNativeFile(path);}
        catch(IOException|RuntimeException|Error failure){load.scope().quarantine();throw failure;}
    }
    public static void beforeBackup(Player player,String suffix){
        if(!(player instanceof ServerPlayer p) || world(p.server).isEmpty())return;
        Load load=requireLoad(p);
        try{if(!suffix.equals(".dat"))throw new IOException("Unexpected backup source");load.receipt().allowEmptyFallback();}
        catch(IOException failure){load.scope().quarantine();throw new UncheckedIOException(failure);}
    }
    public static void loadData(Player player,CompoundTag data,boolean fromFile){
        if(!(player instanceof ServerPlayer p)){player.load(data);return;}
        LegacyRetrievalSaveScope.child(p.server,fromFile?PLAYER_FILE_LOAD:PLAYER_LOAD,PLAYER_NBT_LOAD,player,()->{player.load(data);return null;});
    }
    public static void beforePlayerDataLoad(ServerPlayer player,CompoundTag data){
        if(world(player.server).isEmpty())return;
        Load load=requireLoad(player);
        try{load.receipt().acceptPayload(player.getUUID(),data);}
        catch(IOException failure){load.scope().quarantine();throw new UncheckedIOException(failure);}
    }
    private static Load requireLoad(ServerPlayer player){
        Load load=LOAD.get();
        if(load==null){LegacyRetrievalSaveScope.denyLifecycle(player.server,null,"unscoped bound-world player load");throw new IllegalStateException("Bound-world player load requires its admission receipt");}
        if(load.player()!=player)throw poison(load,"Different player during admission");load.scope().assertHeld();return load;
    }
    private static IllegalStateException poison(Load load,String message){load.scope().quarantine();return new IllegalStateException(message);}
    private static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("World/player lifecycle is not on the server thread");}
    private static void requireEmpty(MinecraftServer server){if(!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Startup recovery requires no active players");}
    private static boolean hasMetadata(MinecraftServer server){
        Path path=server.getWorldPath(LevelResource.ROOT).resolve(LegacyQuestWorldStore.DIRECTORY);
        try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException absent){return false;}
        catch(IOException failure){throw new UncheckedIOException("Cannot inspect world binding",failure);}
    }
}
