import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.State;
import org.gtnhmigration.compat.LegacyRetrievalProgressCodec;
import org.gtnhmigration.compat.LegacyRetrievalProgressCodec.ReadResult;

/** Real 1.21.1 NBT, plus unchanged source methods behind old-name adapters. */
public class LegacyRetrievalProgressCodecCheck {
    static final UUID A=new UUID(1,1), B=new UUID(2,2), C=new UUID(-1,-1);
    static int assertions, sourceComparisons, binaryRoundTrips;
    static void require(boolean ok,String reason) {
        assertions++; if (!ok) throw new AssertionError(reason);
    }
    static CompoundTag row(UUID id,int... values) {
        CompoundTag row=new CompoundTag(); row.putString("uuid",id.toString()); row.putIntArray("data",values); return row;
    }
    static CompoundTag legacy(List<CompoundTag> rows,UUID... completed) {
        CompoundTag payload=new CompoundTag();
        ListTag progress=new ListTag(); rows.forEach(progress::add); payload.put("userProgress",progress);
        ListTag done=new ListTag(); for (UUID id:completed) done.add(StringTag.valueOf(id.toString()));
        payload.put("completeUsers",done); return payload;
    }
    static CompoundTag binary(CompoundTag payload,boolean compressed) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if (compressed) NbtIo.writeCompressed(payload,bytes);
        else NbtIo.write(payload,new DataOutputStream(bytes));
        CompoundTag result=compressed
                ? NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()),NbtAccounter.create(8_000_000))
                : NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),NbtAccounter.create(8_000_000));
        binaryRoundTrips++; require(payload.equals(result),"NBT binary changed the tree"); return result;
    }
    static State compare(CompoundTag payload,int count,State prior,boolean merge) {
        CompoundTag before=payload.copy();
        ReadResult result=LegacyRetrievalProgressCodec.readLegacy(payload,count,prior,merge);
        require(result.active(),"Valid source input rejected: "+result.problems());
        State expected=BqProgressReference.read(payload,count,prior,merge);
        sourceComparisons++;
        require(result.state().orElseThrow().equals(expected),"Source read mismatch");
        require(payload.equals(before) && result.original().equals(before),"Read mutated or lost original");
        return result.state().orElseThrow();
    }
    static void blocked(CompoundTag payload,boolean modern,String code) throws Exception {
        CompoundTag before=binary(payload,true);
        ReadResult result=modern ? LegacyRetrievalProgressCodec.readModern(payload,2,State.empty(),false)
                : LegacyRetrievalProgressCodec.readLegacy(payload,2,State.empty(),false);
        require(!result.active() && result.state().isEmpty(),"Unsupported document activated: "+code);
        require(result.problems().stream().anyMatch(p->p.code().equals(code)),"Missing diagnostic "+code+": "+result.problems());
        payload.putString("caller_mutation","must not leak");
        require(result.original().equals(before),"Caller mutated preserved document");
        CompoundTag returned=result.original(); returned.putString("result_mutation","must not leak");
        require(result.original().equals(before),"Preserved document exposed a mutable reference");
        require(binary(result.original(),false).equals(before),"Unsupported document could not be saved unchanged");
    }
    static CompoundTag withoutVersion(CompoundTag encoded) { CompoundTag copy=encoded.copy(); copy.remove("schema_version"); return copy; }
    public static void main(String[] args) throws Exception {
        CompoundTag original=legacy(List.of(row(A,9,5),row(B,1)),C);
        State loaded=compare(binary(original,true),2,State.empty(),false);
        require(loaded.progress().get(B).equals(List.of(1,0)),"Short array not zero padded");
        require(loaded.completed().equals(Set.of(C)) && !loaded.completed().contains(A),"Completion inferred from counters");
        require(!loaded.progress().containsKey(C),"Completed-only user acquired a fabricated row");

        CompoundTag oldListRow=row(A); ListTag oldList=new ListTag(); oldList.add(IntTag.valueOf(3)); oldList.add(IntTag.valueOf(7));
        oldList.add(IntTag.valueOf(11)); oldListRow.put("data",oldList);
        CompoundTag oldListPayload=legacy(List.of(oldListRow));
        State listLoaded=compare(binary(oldListPayload,false),2,State.empty(),false);
        require(listLoaded.progress().get(A).equals(List.of(3,7)),"Legacy list prefix not retained");
        ReadResult rawKept=LegacyRetrievalProgressCodec.readLegacy(oldListPayload,2,State.empty(),false);
        require(rawKept.original().getList("userProgress",Tag.TAG_COMPOUND).getCompound(0)
                .getList("data",Tag.TAG_INT).size()==3,"Truncated suffix missing from preserved original");

        State prior=new State(Map.of(A,List.of(9,9),B,List.of(2,8)),Set.of(A));
        CompoundTag duplicates=legacy(List.of(row(A,3),row(A,1,4)),C);
        State merged=compare(duplicates,2,prior,true);
        require(merged.progress().get(A).equals(List.of(1,4)) && merged.progress().get(B).equals(List.of(2,8)),
                "Merge must replace last duplicate's row and retain absent users");
        require(merged.completed().equals(Set.of(A,C)),"Merge completion is not a union");
        State replaced=compare(duplicates,2,prior,false);
        require(!replaced.progress().containsKey(B) && replaced.completed().equals(Set.of(C)),"Replacement retained old state");
        compare(new CompoundTag(),2,prior,false);
        CompoundTag missingData=row(A); missingData.remove("data");
        require(compare(legacy(List.of(missingData)),2,State.empty(),false).progress().get(A).equals(List.of(0,0)),
                "Source missing-data meaning changed");

        // Disk loading and the live getter must remain distinct operations.
        State oldShape=new State(Map.of(A,List.of(4,8,12)),Set.of(A));
        State disk=compare(legacy(List.of(row(A,4,8,12)),A),2,State.empty(),false);
        require(disk.progress().get(A).equals(List.of(4,8)),"Disk shape change must keep the prefix");
        require(Arrays.equals(BqProgressReference.liveGetter(oldShape,A,2),new int[]{0,0}),"Source live getter changed");
        require(disk.completed().contains(A),"Shape normalization changed completed users");

        CompoundTag modern=LegacyRetrievalProgressCodec.writeModern(oldShape);
        require(modern.getList("userProgress",Tag.TAG_COMPOUND).getCompound(0).getIntArray("data").length==3,
                "Writer must not normalize live rows");
        ReadResult modernRead=LegacyRetrievalProgressCodec.readModern(binary(modern,true),2,State.empty(),false);
        require(modernRead.state().orElseThrow().equals(disk),"Modern disk load does not use source shape semantics");
        for (List<UUID> selection:Arrays.<List<UUID>>asList(null,List.of(),List.of(A),List.of(C,B))) {
            CompoundTag actual=withoutVersion(LegacyRetrievalProgressCodec.writeModern(prior,selection));
            CompoundTag expected=BqProgressReference.write(prior,selection);
            sourceComparisons++; require(actual.equals(expected),"Source writer/filter mismatch");
        }

        CompoundTag unknownVersion=modern.copy(); unknownVersion.putInt("schema_version",99); blocked(unknownVersion,true,"unsupported_version");
        CompoundTag badVersion=modern.copy(); badVersion.putLong("schema_version",1); blocked(badVersion,true,"expected_int_version");
        blocked(original.copy(),true,"expected_int_version");
        blocked(modern.copy(),false,"unknown_field");
        CompoundTag rootExtra=original.copy(); rootExtra.putLongArray("future_data",new long[]{7,9}); blocked(rootExtra,false,"unknown_field");
        CompoundTag rowExtra=legacy(List.of(row(A,1))); rowExtra.getList("userProgress",Tag.TAG_COMPOUND).getCompound(0).putString("future_row","retain");
        blocked(rowExtra,false,"unknown_field");
        CompoundTag wrongRows=new CompoundTag(); wrongRows.putIntArray("userProgress",new int[]{4,5}); blocked(wrongRows,false,"unexpected_list_type");
        CompoundTag wrongComplete=new CompoundTag(); wrongComplete.putString("completeUsers",A.toString()); blocked(wrongComplete,false,"unexpected_list_type");
        CompoundTag badId=legacy(List.of(row(A,1))); badId.getList("userProgress",Tag.TAG_COMPOUND).getCompound(0).putString("uuid","not-a-uuid");
        blocked(badId,false,"invalid_uuid");
        CompoundTag badComplete=legacy(List.of()); badComplete.getList("completeUsers",Tag.TAG_STRING).add(StringTag.valueOf("bad"));
        blocked(badComplete,false,"invalid_uuid");
        CompoundTag missingId=legacy(List.of(new CompoundTag())); blocked(missingId,false,"expected_string_uuid");
        blocked(legacy(List.of(row(A,1,-1))),false,"unsupported_negative_progress");
        blocked(legacy(List.of(row(A,1,2,-1))),false,"unsupported_negative_progress");
        CompoundTag wrongData=row(A); wrongData.putLongArray("data",new long[]{1,2});
        blocked(legacy(List.of(wrongData)),false,"expected_int_array_or_legacy_int_list");
        CompoundTag wrongList=row(A); ListTag shorts=new ListTag(); shorts.add(ShortTag.valueOf((short)1)); wrongList.put("data",shorts);
        blocked(legacy(List.of(wrongList)),false,"expected_int_array_or_legacy_int_list");
        require(!LegacyRetrievalProgressCodec.readLegacy(original,0,prior,false).active(),"Empty engine configuration activated");

        Random random=new Random(388912101L);
        for (int round=0;round<600;round++) {
            int count=1+random.nextInt(5);
            List<CompoundTag> rows=new ArrayList<>();
            for (int i=0;i<random.nextInt(7);i++) {
                UUID id=List.of(A,B,C).get(random.nextInt(3)); int[] values=new int[random.nextInt(8)];
                for (int k=0;k<values.length;k++) values[k]=random.nextInt(50);
                CompoundTag record=row(id,values);
                if (random.nextBoolean()) { ListTag valuesList=new ListTag(); for (int n:values) valuesList.add(IntTag.valueOf(n)); record.put("data",valuesList); }
                rows.add(record);
            }
            CompoundTag payload=legacy(rows,random.nextBoolean()?new UUID[]{A,C}:new UUID[]{});
            State current=compare(payload,count,prior,random.nextBoolean());
            CompoundTag saved=LegacyRetrievalProgressCodec.writeModern(current);
            sourceComparisons++; require(withoutVersion(saved).equals(BqProgressReference.write(current,null)),"Random source write mismatch");
            State reloaded=LegacyRetrievalProgressCodec.readModern(binary(saved,random.nextBoolean()),count,State.empty(),false).state().orElseThrow();
            sourceComparisons++; require(reloaded.equals(BqProgressReference.read(withoutVersion(saved),count,State.empty(),false)),"Random modern source reload mismatch");
        }
        System.out.println("{\"test\":\"source_differential_retrieval_progress_codec\",\"passed\":true,\"assertions\":"+assertions
                +",\"source_comparisons\":"+sourceComparisons+",\"binary_nbt_roundtrips\":"+binaryRoundTrips
                +",\"actual_minecraft_nbt\":true,\"runtime_integrated\":false,\"ftb_storage_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}
