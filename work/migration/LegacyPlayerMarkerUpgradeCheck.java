package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyQuestProgressFileStore.Head;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;
import static org.gtnhmigration.compat.LegacyQuestWorldStoreCheck.*;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.sameNbt;

/** Synthetic player/world files, production archive/write/recovery/admission. */
public final class LegacyPlayerMarkerUpgradeCheck {
    static int assertions;
    interface Action{void run()throws Exception;}
    static void yes(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void no(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static Path file(Path world,Authority authority){return authority==Authority.WORLD_PLAYER?world.resolve("level.dat"):world.resolve("playerdata").resolve(ACTOR+".dat");}
    static Path maintenance(Path world){return metadata(world).resolve(LegacyPlayerMarkerUpgrade.DIRECTORY);}
    static CompoundTag legacy(CompoundTag modern){
        CompoundTag copy=modern.copy(),forge=copy.getCompound("NeoForgeData"),persisted=forge.getCompound(LegacyRetrievalTransactionJournal.PERSISTED_KEY);
        CompoundTag marker=persisted.getCompound(LegacyRetrievalTransactionJournal.MARKER_KEY).copy();marker.putInt("schema_version",1);
        persisted.remove(LegacyRetrievalTransactionJournal.MARKER_KEY);if(persisted.isEmpty())forge.remove(LegacyRetrievalTransactionJournal.PERSISTED_KEY);
        forge.put(LegacyRetrievalTransactionJournal.MARKER_KEY,marker);return copy;
    }
    static CompoundTag richPlayer(int count){
        CompoundTag player=player(count),foreign=new CompoundTag();player.getList("Inventory",10).getCompound(0).putDouble("fixture_nan",Double.NaN);foreign.putByteArray("bytes",new byte[]{0,-128,127});foreign.putIntArray("ints",new int[]{Integer.MIN_VALUE,17,Integer.MAX_VALUE});foreign.putLong("long",Long.MIN_VALUE);foreign.putFloat("zero",-0.0f);foreign.putDouble("nan",Double.NaN);foreign.putString("label","保留其他模组数据");player.put("foreign:opaque",foreign);
        CompoundTag forge=new CompoundTag();forge.put("other_mod",foreign.copy());player.put("NeoForgeData",forge);return player;
    }
    static CompoundTag setup(LegacyQuestWorldStore world,Authority authority)throws Exception{
        var pending=journal(world).prepare(world.ledger().current().map(Head::sha256),ACTOR,richPlayer(5),richPlayer(3),after(),authority);
        CompoundTag modern=pending.playerDataToPersist();writePlayer(world.worldDirectory(),modern);writeWorld(world.worldDirectory(),modern);world.recoverOffline(()->{});
        writePlayer(world.worldDirectory(),legacy(modern));if(authority==Authority.WORLD_PLAYER)writeWorld(world.worldDirectory(),legacy(modern));return modern;
    }
    static void archived(Path world,byte[] raw)throws Exception{
        Path blob=maintenance(world).resolve("blobs").resolve(LegacyQuestProgressStoreCodec.digest(raw)+".gz");
        yes(Files.isRegularFile(blob) && Arrays.equals(Files.readAllBytes(blob),raw),"Original or target gzip archive lost bytes");
    }
    static LegacyQuestProgressFileStore auxiliary(LegacyQuestWorldStore world)throws IOException{return LegacyQuestProgressFileStore.open(maintenance(world.worldDirectory()).resolve("ledger"),world.binding(),world.catalog());}
    static void admission(LegacyQuestWorldStore store,Authority authority,CompoundTag cache)throws Exception{
        var receipt=LegacyQuestPlayerAdmission.begin(store,ACTOR,authority==Authority.WORLD_PLAYER,cache);
        var data=authority==Authority.WORLD_PLAYER?cache:receipt.readNativeFile(file(store.worldDirectory(),Authority.PLAYER_FILE));receipt.acceptPayload(ACTOR,data);receipt.finish(Optional.of(data),ACTOR,data.getCompound("NeoForgeData"));
    }
    static void field(DataOutputStream out,String name,Tag tag)throws IOException{out.writeByte(tag.getId());out.writeUTF(name);tag.write(out);}
    static void compound(DataOutputStream out,String name)throws IOException{out.writeByte(10);out.writeUTF(name);}
    /** Written independently from the production parser/editor, including all
     * NBT payload types, signed zeros and noncanonical NaNs on the wire. */
    static void proof(DataOutputStream out)throws IOException{
        compound(out,"binary:proof");field(out,"b",ByteTag.valueOf((byte)-7));field(out,"s",ShortTag.valueOf((short)-1234));field(out,"i",IntTag.valueOf(Integer.MIN_VALUE));field(out,"l",LongTag.valueOf(Long.MIN_VALUE));
        out.writeByte(5);out.writeUTF("negative_float_zero");out.writeInt(0x80000000);
        out.writeByte(6);out.writeUTF("negative_double_zero");out.writeLong(0x8000000000000000L);
        field(out,"bytes",new ByteArrayTag(new byte[]{0,-128,127}));field(out,"str",StringTag.valueOf("opaque\u0000原样"));
        out.writeByte(9);out.writeUTF("floats");out.writeByte(5);out.writeInt(2);out.writeInt(0x7fc12345);out.writeInt(0xffc12345);
        compound(out,"nested");out.writeByte(6);out.writeUTF("nan");out.writeLong(0x7ff8000000012345L);out.writeByte(0);
        field(out,"ints",new IntArrayTag(new int[]{Integer.MIN_VALUE,0,Integer.MAX_VALUE}));field(out,"longs",new LongArrayTag(new long[]{Long.MIN_VALUE,0,Long.MAX_VALUE}));out.writeByte(0);
    }
    static byte[] golden(CompoundTag modern,Authority authority,boolean upgraded)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);compound(out,"root 原名");
        if(authority==Authority.WORLD_PLAYER){proof(out);compound(out,"Data");field(out,"Time",LongTag.valueOf(123456789));compound(out,"Player");}
        field(out,"UUID",modern.get("UUID"));field(out,"Inventory",modern.get("Inventory"));proof(out);compound(out,"NeoForgeData");
        field(out,"other_mod",modern.getCompound("NeoForgeData").get("other_mod"));
        CompoundTag marker=modern.getCompound("NeoForgeData").getCompound("PlayerPersisted").getCompound(LegacyRetrievalTransactionJournal.MARKER_KEY).copy();
        if(!upgraded){marker.putInt("schema_version",1);field(out,LegacyRetrievalTransactionJournal.MARKER_KEY,marker);}
        compound(out,"PlayerPersisted");proof(out);if(upgraded)field(out,LegacyRetrievalTransactionJournal.MARKER_KEY,marker);out.writeByte(0);out.writeByte(0);
        field(out,"opaque",modern.get("opaque"));out.writeByte(0);
        if(authority==Authority.WORLD_PLAYER){proof(out);out.writeByte(0);field(out,"outside_data",StringTag.valueOf("retain"));out.writeByte(0);}
        return bytes.toByteArray();
    }
    static byte[] gzip(byte[] raw)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(raw);}return bytes.toByteArray();}
    static byte[] gunzip(byte[] raw)throws IOException{try(var gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw))){return gzip.readAllBytes();}}
    static void binaryFidelity(Path root)throws Exception{
        for(Authority authority:Authority.values()){
            Path dir=root.resolve(authority.name());try(var store=create(dir)){
                var modern=setup(store,authority);byte[] before=withHeader(gzip(golden(modern,authority,false)));Files.write(file(dir,authority),before);
                var cache=authority==Authority.WORLD_PLAYER?LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data():null;
                yes(LegacyPlayerMarkerUpgrade.promote(store,ACTOR,authority,authority==Authority.WORLD_PLAYER?()->cache:null,()->{}),"Raw golden marker did not upgrade");
                byte[] result=Files.readAllBytes(file(dir,authority));yes(Arrays.equals(gunzip(result),golden(modern,authority,true)),"Binary upgrade differs from independently written golden NBT (NaNs/negative zero/order/name/foreign fields)");
                archived(dir,before);archived(dir,result);admission(store,authority,cache);
            }
        }
    }
    static void ambiguousRawPaths(Path root)throws Exception{
        for(Authority authority:Authority.values())for(String name:List.of("NeoForgeData",LegacyRetrievalTransactionJournal.MARKER_KEY)){
            Path dir=root.resolve(authority.name()+"-"+name.replace(':','_'));try(var store=create(dir)){
                var modern=setup(store,authority);byte[] raw=golden(modern,authority,false);ByteArrayOutputStream named=new ByteArrayOutputStream();compound(new DataOutputStream(named),name);byte[] needle=named.toByteArray();int offset=-1,hits=0;
                for(int i=0;i<=raw.length-needle.length;i++){boolean match=true;for(int j=0;j<needle.length;j++)if(raw[i+j]!=needle[j]){match=false;break;}if(match){offset=i;hits++;}}
                yes(hits==1,"Ambiguous fixture needle");ByteArrayOutputStream malformed=new ByteArrayOutputStream();malformed.write(raw,0,offset);malformed.write(needle);malformed.write(0);malformed.write(raw,offset,raw.length-offset);Files.write(file(dir,authority),gzip(malformed.toByteArray()));
                // Native reader discards the first duplicate. Binary editor must
                // reject ambiguity and retain both original raw occurrences.
                var cache=authority==Authority.WORLD_PLAYER?LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data():null;var before=bytes(dir);
                no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,authority,authority==Authority.WORLD_PLAYER?()->cache:null,()->{}),"duplicate raw marker path");yes(bytes(dir).equals(before),"Ambiguous binary input changed on rejection");
            }
        }
    }
    static void nbtValues(Path root)throws Exception{
        CompoundTag a=richPlayer(5),b=richPlayer(5);yes(sameNbt(a,b),"Separate NaN trees compare unequal");
        a.getList("Inventory",10).getCompound(0).putFloat("float_nan",Float.intBitsToFloat(0x7fc12345));
        b.getList("Inventory",10).getCompound(0).putFloat("float_nan",Float.intBitsToFloat(0x7fc12345));yes(sameNbt(a,b),"Equal float NaN payloads compare unequal");
        b.getList("Inventory",10).getCompound(0).putFloat("float_nan",Float.intBitsToFloat(0x7fc12346));yes(!sameNbt(a,b),"Different NaN payloads compare equal");
        yes(!sameNbt(IntTag.valueOf(1),ByteTag.valueOf((byte)1)),"NBT tag type lost");
        try(var store=create(root)){
            a.getCompound("foreign:opaque").putDouble("nan",Double.longBitsToDouble(0x7ff8000000012345L));b=a.copy();b.getList("Inventory",10).getCompound(0).putInt("count",3);
            var p=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,a,b,after());
            var restored=journal(store).pending().orElseThrow();
            yes(Double.doubleToRawLongBits(restored.originalPlayerData().getCompound("foreign:opaque").getDouble("nan"))==0x7ff8000000012345L,"Journal canonicalized double NaN payload");
            yes(Float.floatToRawIntBits(restored.playerDataToPersist().getList("Inventory",10).getCompound(0).getFloat("float_nan"))==0x7fc12345,"Journal canonicalized float NaN payload");
            CompoundTag persisted=p.playerDataToPersist();persisted.getList("Inventory",10).getCompound(0).putFloat("float_nan",Float.intBitsToFloat(0x7fc12345));
            yes(restored.decisionFor(persisted)==LegacyRetrievalTransactionJournal.Decision.COMMIT,"Equivalent NaN inventory refused recovery");
            persisted.getList("Inventory",10).getCompound(0).putFloat("float_nan",Float.intBitsToFloat(0x7fc12346));no(()->restored.decisionFor(persisted),"different NaN inventory payload");
        }
    }
    static void ordinary(Path root)throws Exception{
        Path dir=root.resolve("uuid");try(var store=create(dir)){
            var modern=setup(store,Authority.PLAYER_FILE);Path path=file(dir,Authority.PLAYER_FILE);Files.write(path,withHeader(Files.readAllBytes(path)));byte[] before=Files.readAllBytes(path),world=Files.readAllBytes(file(dir,Authority.WORLD_PLAYER));
            byte[] old={7,0,-1};Path backup=path.resolveSibling(ACTOR+".dat_old");Files.write(backup,old);var head=store.ledger().current().orElseThrow();int[] barriers={0};
            yes(LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->barriers[0]++),"Legacy UUID marker was not promoted");
            yes(barriers[0]>=3,"Marker writer missed exclusion checks");yes(store.ledger().current().orElseThrow().equals(head),"Marker upgrade changed quest ledger");
            yes(sameNbt(LegacyPlayerDataFileReader.read(dir.resolve("playerdata"),ACTOR).data(),modern),"UUID promotion lost unrelated NBT");
            yes(Arrays.equals(Files.readAllBytes(backup),old) && Arrays.equals(Files.readAllBytes(file(dir,Authority.WORLD_PLAYER)),world),"UUID promotion changed backup/world");
            archived(dir,before);archived(dir,Files.readAllBytes(path));admission(store,Authority.PLAYER_FILE,null);
            var snapshot=bytes(dir);yes(!LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{}),"Already persistent marker rewritten");yes(!LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"Resolved upgrade repeated");yes(bytes(dir).equals(snapshot),"No-op changed persisted files");
            // A later transaction may change marker identity. Historical upgrade
            // records remain valid without imposing their old marker on today.
            CompoundTag next=modern.copy();next.getList("Inventory",10).getCompound(0).putInt("count",1);
            var p=journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,modern,next,after());writePlayer(dir,p.playerDataToPersist());store.recoverOffline(()->{});var second=p.playerDataToPersist();writePlayer(dir,legacy(second));
            var newHead=store.ledger().current().orElseThrow();yes(LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{}),"Second transaction marker blocked by archived history");
            yes(store.ledger().current().orElseThrow().equals(newHead) && sameNbt(LegacyPlayerDataFileReader.read(dir.resolve("playerdata"),ACTOR).data(),second),"Second promotion changed progress or inventory");
            try(var aux=auxiliary(store)){yes(aux.current().orElseThrow().frame().revision()==5,"Upgrade history was replaced");}
        }
        Path fresh=root.resolve("fresh");try(var store=create(fresh)){
            Files.createDirectories(fresh.resolve("playerdata"));yes(!LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{}),"New player gained marker file");yes(!Files.exists(maintenance(fresh)),"No-op created maintenance state");
            writePlayer(fresh,player(5));yes(!LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{}),"Unmarked player changed");yes(!Files.exists(maintenance(fresh)),"Unmarked player created maintenance state");
        }
    }
    static void owners(Path root)throws Exception{
        for(String kind:List.of("legacy-mirror","modern-mirror","missing-mirror")){
            Path dir=root.resolve(kind);try(var store=create(dir)){
                CompoundTag modern=setup(store,Authority.WORLD_PLAYER),cached=legacy(modern);cached.putString("loaded:extra","keep loaded-only fields");
                if(kind.equals("modern-mirror"))writePlayer(dir,modern);if(kind.equals("missing-mirror"))Files.delete(file(dir,Authority.PLAYER_FILE));
                var source=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR);byte[] before=source.originalBytes();CompoundTag expected=source.documentData();expected.getCompound("Data").put("Player",modern);
                byte[] mirror=Files.exists(file(dir,Authority.PLAYER_FILE))?Files.readAllBytes(file(dir,Authority.PLAYER_FILE)):null;
                Path backup=dir.resolve("level.dat_old");Files.write(backup,new byte[]{9,-3,0});List<LegacyPlayerMarkerUpgrade.Boundary> points=new ArrayList<>();
                yes(LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cached,()->{},points::add),"Owner marker did not promote");
                yes(sameNbt(LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).documentData(),expected),"World metadata changed during marker upgrade");
                var expectedCache=modern.copy();expectedCache.putString("loaded:extra","keep loaded-only fields");yes(sameNbt(cached,expectedCache),"Loaded cache lost opaque data");
                yes(Arrays.equals(Files.readAllBytes(backup),new byte[]{9,-3,0}),"World backup changed");archived(dir,before);archived(dir,Files.readAllBytes(file(dir,Authority.WORLD_PLAYER)));
                if(mirror==null)yes(!Files.exists(file(dir,Authority.PLAYER_FILE)),"Upgrade invented absent UUID mirror");
                else{archived(dir,mirror);yes(sameNbt(LegacyPlayerDataFileReader.read(dir.resolve("playerdata"),ACTOR).data(),modern),"Owner mirror did not match upgraded marker");}
                if(kind.equals("legacy-mirror"))yes(points.indexOf(LegacyPlayerMarkerUpgrade.Boundary.PLAYER_PUBLISHED)<points.indexOf(LegacyPlayerMarkerUpgrade.Boundary.WORLD_PUBLISHED),"World published before mirror");
                admission(store,Authority.WORLD_PLAYER,cached);
            }
        }
        Path dir=root.resolve("cache-only");try(var store=create(dir)){
            var modern=setup(store,Authority.WORLD_PLAYER);writePlayer(dir,modern);writeWorld(dir,modern);var cached=legacy(modern);var before=bytes(dir);
            yes(!LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cached,()->{}),"Cache-only change wrote files");yes(sameNbt(cached,modern) && bytes(dir).equals(before),"Cache-only marker promotion lost state");admission(store,Authority.WORLD_PLAYER,cached);
        }
    }
    static void cacheOnlyMirrorRace(Path root)throws Exception{
        for(int point:List.of(2,3)){
            Path dir=root.resolve("barrier-"+point);try(var store=create(dir)){
                var modern=setup(store,Authority.WORLD_PLAYER);writeWorld(dir,modern);Files.delete(file(dir,Authority.PLAYER_FILE));var cache=legacy(modern);int[] barriers={0};byte[] world=Files.readAllBytes(file(dir,Authority.WORLD_PLAYER));var head=store.ledger().current().orElseThrow();
                no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{if(++barriers[0]==point)try{writePlayer(dir,modern);}catch(Exception failure){throw new IllegalStateException(failure);}}),"mirror appeared during cache-only promotion");
                yes(Arrays.equals(world,Files.readAllBytes(file(dir,Authority.WORLD_PLAYER))),"Cache-only mirror race overwrote world");
                yes(sameNbt(LegacyPlayerDataFileReader.read(dir.resolve("playerdata"),ACTOR).data(),modern),"Cache-only race overwrote external mirror");
                yes(store.ledger().current().orElseThrow().equals(head) && !Files.exists(maintenance(dir)),"Cache-only race changed maintenance/progress");
            }
        }
    }
    static void unknowns(Path root)throws Exception{
        for(int scenario=0;scenario<6;scenario++){
            Path dir=root.resolve("bad-"+scenario);try(var store=create(dir)){
                var modern=setup(store,Authority.WORLD_PLAYER);CompoundTag cache=legacy(modern);int index=scenario;
                switch(scenario){case 0->cache.getList("Inventory",10).getCompound(0).putInt("count",99);case 1->cache.putUUID("UUID",OTHER);case 2->{CompoundTag mirror=legacy(modern);mirror.getList("Inventory",10).getCompound(0).putInt("count",99);writePlayer(dir,mirror);}case 3->Files.write(file(dir,Authority.WORLD_PLAYER),new byte[]{1,2});case 4->Files.write(file(dir,Authority.PLAYER_FILE),new byte[]{1,2});case 5->{writeWorld(dir,modern);CompoundTag mirror=modern.copy();mirror.getList("Inventory",10).getCompound(0).putInt("count",99);writePlayer(dir,mirror);cache.put("NeoForgeData",modern.getCompound("NeoForgeData").copy());}}
                var before=bytes(dir);no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{}),"unknown state "+index);yes(bytes(dir).equals(before),"Rejected marker upgrade changed files");
            }
        }
        Path absent=root.resolve("cache-absent");try(var store=create(absent)){
            setup(store,Authority.WORLD_PLAYER);var before=bytes(absent);no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->null,()->{}),"absent native loaded cache");yes(bytes(absent).equals(before),"Missing cache wrote files");
        }
    }
    static void interruptions(Path root)throws Exception{
        for(String point:List.of("intent","mirror","cache")){
            Path dir=root.resolve(point);try(var store=create(dir)){
                var modern=setup(store,Authority.WORLD_PLAYER);var cache=legacy(modern);byte[] oldWorld=Files.readAllBytes(file(dir,Authority.WORLD_PLAYER)),oldPlayer=Files.readAllBytes(file(dir,Authority.PLAYER_FILE));var main=store.ledger().current().orElseThrow();
                no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.WORLD_PLAYER,()->cache,()->{},boundary->{
                    if(point.equals("intent") && boundary==LegacyPlayerMarkerUpgrade.Boundary.PREPARED){var changed=legacy(modern);changed.putString("foreign:write","preserve");writePlayer(dir,changed);}
                    if(point.equals("mirror") && boundary==LegacyPlayerMarkerUpgrade.Boundary.PLAYER_PUBLISHED){var document=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).documentData();document.getCompound("Data").putLong("Time",9876);NbtIo.writeCompressed(document,file(dir,Authority.WORLD_PLAYER));}
                    if(point.equals("cache") && boundary==LegacyPlayerMarkerUpgrade.Boundary.CACHE_UPDATED)cache.put("NeoForgeData",legacy(modern).getCompound("NeoForgeData").copy());
                }),"unexpected change at "+point);
                yes(store.ledger().current().orElseThrow().equals(main),"Maintenance failure changed quest progress");try(var aux=auxiliary(store)){yes(aux.current().orElseThrow().frame().revision()==2,"Failure incorrectly resolved maintenance");}
                if(!point.equals("cache")){var preserved=bytes(dir);no(()->LegacyPlayerMarkerUpgrade.resume(store,()->cache,()->{}),"unknown external file after "+point);yes(bytes(dir).equals(preserved),"Recovery overwrote unknown file");}
                if(point.equals("intent"))Files.write(file(dir,Authority.PLAYER_FILE),oldPlayer);if(point.equals("mirror"))Files.write(file(dir,Authority.WORLD_PLAYER),oldWorld);
                yes(LegacyPlayerMarkerUpgrade.resume(store,()->cache,()->{}),"Restored proven state did not resume");yes(sameNbt(cache,modern),"Resume did not promote actual cache");admission(store,Authority.WORLD_PLAYER,cache);archived(dir,oldWorld);archived(dir,oldPlayer);
            }
        }
        Path dir=root.resolve("progress-race");try(var store=create(dir)){
            var modern=setup(store,Authority.PLAYER_FILE);byte[] before=Files.readAllBytes(file(dir,Authority.PLAYER_FILE));
            no(()->LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{},boundary->{if(boundary==LegacyPlayerMarkerUpgrade.Boundary.PREPARED){CompoundTag a=legacy(modern),b=a.copy();b.getList("Inventory",10).getCompound(0).putInt("count",1);journal(store).prepare(store.ledger().current().map(Head::sha256),ACTOR,a,b,after());}}),"progress changed during marker intent");
            yes(Arrays.equals(before,Files.readAllBytes(file(dir,Authority.PLAYER_FILE))) && journal(store).pending().isPresent(),"Overlapping intent lost player/progress data");
        }
    }
    static void integrity(Path root)throws Exception{
        Path dir=root.resolve("blob");try(var store=create(dir)){
            setup(store,Authority.PLAYER_FILE);byte[] old=Files.readAllBytes(file(dir,Authority.PLAYER_FILE));LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{});
            Path blob=maintenance(dir).resolve("blobs").resolve(LegacyQuestProgressStoreCodec.digest(old)+".gz");Files.write(blob,new byte[]{1,2,3});var before=bytes(dir);
            no(()->LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"corrupted historical gzip archive");yes(bytes(dir).equals(before),"Corrupted archive was silently replaced");
        }
        Path missing=root.resolve("missing");try(var store=create(missing)){
            setup(store,Authority.PLAYER_FILE);LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{});Path ledger=maintenance(missing).resolve("ledger"),held=root.resolve("held-ledger");Files.move(ledger,held);
            no(()->LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"missing auxiliary ledger");yes(!Files.exists(ledger),"Missing auxiliary history reset to empty");
        }
        Path rollback=root.resolve("rollback");try(var store=create(rollback)){
            setup(store,Authority.PLAYER_FILE);LegacyPlayerMarkerUpgrade.promote(store,ACTOR,Authority.PLAYER_FILE,null,()->{});var head=store.ledger().current().orElseThrow();Path tail=store.ledger().directory().resolve(String.format(Locale.ROOT,"%020d-%s.nbt",head.frame().revision(),head.sha256()));byte[] bytes=Files.readAllBytes(tail);Files.delete(tail);
            no(()->LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"missing referenced quest ledger tail");Files.write(tail,bytes);yes(!LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"Restored history should be resolved");
        }
    }
    static void process(String mode,Path dir,Authority authority,String boundary)throws Exception{
        if(mode.equals("crash")){
            try(var store=create(dir)){
                var modern=setup(store,authority);for(Authority a:Authority.values())Files.write(dir.resolve("fixture-before-"+a+".gz"),Files.readAllBytes(file(dir,a)));
                var cached=legacy(modern);LegacyPlayerMarkerUpgrade.promote(store,ACTOR,authority,authority==Authority.WORLD_PLAYER?()->cached:null,()->{},point->{if(point.name().equals(boundary))Runtime.getRuntime().halt(73);});throw new AssertionError("Missing marker halt boundary");
            }
        }
        try(var store=LegacyQuestWorldStore.open(dir)){
            var cached=LegacyPlayerDataFileReader.readWorldPlayer(dir,ACTOR).data();var main=store.ledger().current().orElseThrow();
            LegacyPlayerMarkerUpgrade.resume(store,authority==Authority.WORLD_PLAYER?()->cached:null,()->{});
            LegacyPlayerMarkerUpgrade.promote(store,ACTOR,authority,authority==Authority.WORLD_PLAYER?()->cached:null,()->{});
            yes(store.ledger().current().orElseThrow().equals(main),"Restart promotion changed main quest head");admission(store,authority,cached);
            archived(dir,Files.readAllBytes(dir.resolve("fixture-before-"+Authority.PLAYER_FILE+".gz")));
            if(authority==Authority.WORLD_PLAYER)archived(dir,Files.readAllBytes(dir.resolve("fixture-before-"+Authority.WORLD_PLAYER+".gz")));
            yes(!LegacyPlayerMarkerUpgrade.resume(store,null,()->{}),"Restart left a pending marker operation");
        }System.out.println("MARKER_RESTART_ADMISSION_OK");
    }
    public static void main(String[] args)throws Exception{
        if(args.length>1){process(args[0],Path.of(args[1]),Authority.valueOf(args[2]),args[3]);return;}
        Path root=Path.of(args[0]);nbtValues(root.resolve("nbt-values"));binaryFidelity(root.resolve("raw-golden"));ambiguousRawPaths(root.resolve("raw-ambiguous"));ordinary(root.resolve("ordinary"));owners(root.resolve("owners"));cacheOnlyMirrorRace(root.resolve("cache-mirror-race"));unknowns(root.resolve("unknowns"));interruptions(root.resolve("interruptions"));integrity(root.resolve("integrity"));
        System.out.println("{\"test\":\"persistent_player_marker_upgrade\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_call_sites_registered\":true,\"game_runtime_verified\":false,\"binary_golden_authorities\":2,\"ieee_bit_fidelity_fixture_verified\":true,\"actual_player_save_fixtures\":0}");
    }
}
