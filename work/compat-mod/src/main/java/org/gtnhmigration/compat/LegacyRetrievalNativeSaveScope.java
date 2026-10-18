package org.gtnhmigration.compat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

/** Native identity binding; requires all guard mixins before acquiring a lease. */
public final class LegacyRetrievalNativeSaveScope {
    public interface ServerAccess {
        LevelStorageSource.LevelStorageAccess gtnhMigration$worldStorage();
        PlayerDataStorage gtnhMigration$playerStorage();
    }
    public interface PlayerListHooks {}
    public interface PlayerFileHooks {}
    public interface WorldFileHooks {}
    public interface OwnerCacheHooks {}
    public interface PlayerLifecycleHooks {}
    public interface PlayerDataLoadHooks {}
    private LegacyRetrievalNativeSaveScope(){}
    public static LegacyRetrievalSaveScope.Lease begin(ServerPlayer player,PlayerDataStorage storage)throws IOException {
        MinecraftServer server=player.getServer();
        if(server==null || !server.isSameThread() || player.isRemoved())throw new IllegalStateException("Inactive native server thread");
        if(!(player instanceof PlayerLifecycleHooks) || !(player instanceof PlayerDataLoadHooks))throw new IllegalStateException("Player lifecycle/load guards are missing");
        return beginServer(server,storage);
    }
    public static LegacyRetrievalSaveScope.Lease beginServer(MinecraftServer server)throws IOException {
        if(!(server instanceof ServerAccess access))throw new IllegalStateException("Native server access hook is missing");
        return beginServer(server,access.gtnhMigration$playerStorage());
    }
    private static LegacyRetrievalSaveScope.Lease beginServer(MinecraftServer server,PlayerDataStorage storage)throws IOException {
        if(!server.isSameThread())throw new IllegalStateException("Wrong server thread");
        if(!(server instanceof ServerAccess access) || !(storage instanceof PlayerFileHooks)
                || !(server.getPlayerList() instanceof PlayerListHooks)
                || !(access.gtnhMigration$worldStorage() instanceof WorldFileHooks)
                || access.gtnhMigration$playerStorage()!=storage)
            throw new IllegalStateException("Required native save hooks or storage identity are missing");
        boolean integrated=!server.isDedicatedServer();
        if(integrated && !(server.getPlayerList() instanceof OwnerCacheHooks))throw new IllegalStateException("Integrated cache entry hook is missing");
        Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path players=storage.getPlayerDir().toPath().toAbsolutePath().normalize();
        if(!players.equals(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize())
                || !world.equals(access.gtnhMigration$worldStorage().getWorldDir().toAbsolutePath().normalize()))
            throw new IllegalStateException("Native storage paths do not belong to this server");
        return LegacyRetrievalSaveScope.begin(List.of(server,server.getPlayerList(),storage,access.gtnhMigration$worldStorage()),List.of(world,players),integrated);
    }
    public static void assertHeld(ServerPlayer player){LegacyRetrievalSaveScope.assertHeld(player.getServer());}
    public static void savePlayer(PlayerDataStorage storage,ServerPlayer player){
        LegacyRetrievalSaveScope.root(player.getServer(),PLAYER_FILE,player,()->{storage.save(player);return null;});
    }
    public static void saveOwner(MinecraftServer server){LegacyRetrievalSaveScope.root(server,EVERYTHING,server,()->server.saveEverything(false,false,false));}
    public static void savePlayers(MinecraftServer server){LegacyRetrievalSaveScope.root(server,PLAYERS,server.getPlayerList(),()->{server.getPlayerList().saveAll();return null;});}
}
