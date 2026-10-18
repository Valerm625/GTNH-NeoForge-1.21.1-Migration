package org.gtnhmigration.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Authority;
import org.gtnhmigration.compat.LegacyPlayerDataFileReader.Snapshot;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.MARKER_KEY;
import static org.gtnhmigration.compat.LegacyRetrievalTransactionJournal.PERSISTED_KEY;

/** Edits named-field spans instead of serializing decoded foreign data.
 * Native NBT normalizes negative zero on read and NaN payloads on write.
 * All bytes outside the marker placement, including the root name and
 * unknown fields, retain their original representation and ordering.
 */
final class LegacyPlayerNbtPatch {
    private record Field(int type,String name,int start,int payload,int end) {}
    final byte[] raw;
    LegacyPlayerNbtPatch(byte[] raw){this.raw=raw;}
    static byte[] promoteMarker(Snapshot snapshot)throws IOException {
        LegacyPlayerNbtPatch nbt=new LegacyPlayerNbtPatch(LegacyPlayerDataFileReader.inflateSingleMember(snapshot.originalBytes()));
        Field root=nbt.new Cursor(0).field(0);require(root.type()==10 && root.end()==nbt.raw.length,"Invalid raw marker document");
        Field player=root;
        if(snapshot.authority()==Authority.WORLD_PLAYER)player=nbt.required(nbt.required(root,"Data"),"Player");
        Field forge=nbt.required(player,"NeoForgeData");List<Field> fields=nbt.fields(forge);
        Field marker=nbt.find(fields,LegacyRetrievalTransactionJournal.MARKER_KEY);
        require(marker!=null && marker.type()==10,"Missing raw legacy marker");
        require(nbt.fields(marker).size()==3,"Duplicate raw marker fields");
        nbt.required(marker,"transaction");nbt.required(marker,"preparation_sha256");
        Field version=nbt.required(marker,"schema_version");require(version.type()==3 && version.end()-version.payload()==4,"Invalid raw marker version");
        require(ByteBuffer.wrap(nbt.raw,version.payload(),4).getInt()==1,"Raw marker is not v1");
        byte[] modern=Arrays.copyOfRange(nbt.raw,marker.start(),marker.end());ByteBuffer.wrap(modern,version.payload()-marker.start(),4).putInt(2);
        Field persisted=nbt.find(fields,LegacyRetrievalTransactionJournal.PERSISTED_KEY);
        byte[] replacement;
        if(persisted==null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF(LegacyRetrievalTransactionJournal.PERSISTED_KEY);out.write(modern);out.writeByte(0);replacement=bytes.toByteArray();}
        else{
            require(persisted.type()==10 && nbt.find(nbt.fields(persisted),LegacyRetrievalTransactionJournal.MARKER_KEY)==null,"Ambiguous raw persisted marker");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(nbt.raw,persisted.start(),persisted.end()-persisted.start()-1);bytes.write(modern);bytes.write(0);replacement=bytes.toByteArray();
        }
        ByteArrayOutputStream value=new ByteArrayOutputStream();value.write(nbt.raw,forge.start(),forge.payload()-forge.start());
        for(Field field:fields){if(field==marker)continue;if(field==persisted)value.write(replacement);else value.write(nbt.raw,field.start(),field.end()-field.start());}
        if(persisted==null)value.write(replacement);value.write(0);
        ByteArrayOutputStream document=new ByteArrayOutputStream();document.write(nbt.raw,0,forge.start());value.writeTo(document);document.write(nbt.raw,forge.end(),nbt.raw.length-forge.end());return document.toByteArray();
    }
    private record MarkerFields(Field legacy,Field persisted,Field marker) {}
    Field root()throws IOException{Field root=new Cursor(0).field(0);require(root.type()==10 && root.end()==raw.length,"Invalid raw NBT document");return root;}
    Field player(Authority authority)throws IOException{Field root=root();return authority==Authority.PLAYER_FILE?root:required(required(root,"Data"),"Player");}
    byte[] bytes(Field field){return field==null?null:Arrays.copyOfRange(raw,field.start(),field.end());}
    MarkerFields markers(Field forge)throws IOException{
        if(forge==null)return new MarkerFields(null,null,null);
        var children=fields(forge);Field legacy=find(children,MARKER_KEY),persisted=find(children,PERSISTED_KEY);
        Field marker=persisted==null?null:find(fields(persisted),MARKER_KEY);
        require(legacy==null || marker==null,"Ambiguous raw transaction markers");
        for(Field value:new Field[]{legacy,marker})if(value!=null){require(fields(value).size()==3,"Duplicate raw marker fields");required(value,"schema_version");required(value,"transaction");required(value,"preparation_sha256");}
        return new MarkerFields(legacy,persisted,marker);
    }
    byte[] compound(Field existing,String name,LinkedHashMap<String,byte[]> replacements)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();Set<String> seen=new HashSet<>();
        if(existing==null){DataOutputStream named=new DataOutputStream(out);named.writeByte(10);named.writeUTF(name);}
        else{
            out.write(raw,existing.start(),existing.payload()-existing.start());
            for(Field child:fields(existing)){
                if(replacements.containsKey(child.name())){require(seen.add(child.name()),"Duplicate raw patched field: "+child.name());byte[] replacement=replacements.get(child.name());if(replacement!=null)out.write(replacement);}
                else out.write(raw,child.start(),child.end()-child.start());
            }
        }
        for(var entry:replacements.entrySet())if(!seen.contains(entry.getKey()) && entry.getValue()!=null)out.write(entry.getValue());out.write(0);return out.toByteArray();
    }
    /** Copies only Inventory and our marker from the authoritative world.
     * Both snapshots must already be classified by the same pending journal.
     * Foreign bytes and all backups remain untouched; this performs no I/O.
     */
    static byte[] restoreMirror(Snapshot mirror,Snapshot world)throws IOException{
        require(mirror.authority()==Authority.PLAYER_FILE && world.authority()==Authority.WORLD_PLAYER && mirror.actor().equals(world.actor()),"Wrong inventory repair authorities");
        LegacyPlayerNbtPatch dst=new LegacyPlayerNbtPatch(LegacyPlayerDataFileReader.inflateSingleMember(mirror.originalBytes()));
        LegacyPlayerNbtPatch src=new LegacyPlayerNbtPatch(LegacyPlayerDataFileReader.inflateSingleMember(world.originalBytes()));
        Field target=dst.player(mirror.authority()),source=src.player(world.authority());
        Field targetForge=dst.find(dst.fields(target),"NeoForgeData"),sourceForge=src.find(src.fields(source),"NeoForgeData");
        MarkerFields before=dst.markers(targetForge),after=src.markers(sourceForge);
        LinkedHashMap<String,byte[]> forgeChanges=new LinkedHashMap<>();forgeChanges.put(MARKER_KEY,src.bytes(after.legacy()));
        if(before.persisted()!=null || after.marker()!=null){LinkedHashMap<String,byte[]> persistedChanges=new LinkedHashMap<>();persistedChanges.put(MARKER_KEY,src.bytes(after.marker()));forgeChanges.put(PERSISTED_KEY,dst.compound(before.persisted(),PERSISTED_KEY,persistedChanges));}
        byte[] forge=targetForge!=null || after.legacy()!=null || after.marker()!=null?dst.compound(targetForge,"NeoForgeData",forgeChanges):null;
        LinkedHashMap<String,byte[]> changes=new LinkedHashMap<>();changes.put("Inventory",src.bytes(src.required(source,"Inventory")));changes.put("NeoForgeData",forge);
        // UUID root has no external length prefix or enclosing document payload.
        return dst.compound(target,target.name(),changes);
    }
    /** Cache-only candidate. Preserve all unrelated loaded-only NBT fields. */
    static CompoundTag restoreCache(CompoundTag mirror,CompoundTag world)throws IOException{
        require(mirror.hasUUID("UUID") && world.hasUUID("UUID") && mirror.getUUID("UUID").equals(world.getUUID("UUID")),"Cache repair UUID mismatch");
        require(mirror.get("Inventory") instanceof ListTag && world.get("Inventory") instanceof ListTag,"Missing cache inventory");
        LegacyRetrievalTransactionJournal.readMarker(mirror);LegacyRetrievalTransactionJournal.readMarker(world);
        CompoundTag result=mirror.copy();result.put("Inventory",world.get("Inventory").copy());
        CompoundTag source=world.getCompound("NeoForgeData"),target=result.getCompound("NeoForgeData");
        Tag legacy=source.get(MARKER_KEY),modern=source.getCompound(PERSISTED_KEY).get(MARKER_KEY);
        target.remove(MARKER_KEY);if(legacy!=null)target.put(MARKER_KEY,legacy.copy());
        if(target.contains(PERSISTED_KEY) || modern!=null){CompoundTag persisted=target.getCompound(PERSISTED_KEY);persisted.remove(MARKER_KEY);if(modern!=null)persisted.put(MARKER_KEY,modern.copy());target.put(PERSISTED_KEY,persisted);}
        if(result.contains("NeoForgeData") || legacy!=null || modern!=null)result.put("NeoForgeData",target);
        return result;
    }
    Field required(Field parent,String name)throws IOException{Field result=find(fields(parent),name);require(result!=null,"Missing raw NBT field: "+name);return result;}
    Field find(List<Field> fields,String name)throws IOException{Field result=null;for(Field field:fields)if(field.name().equals(name)){require(result==null,"Duplicate raw marker-path field: "+name);result=field;}return result;}
    List<Field> fields(Field compound)throws IOException{
        require(compound.type()==10,"Raw marker path is not a compound");Cursor cursor=new Cursor(compound.payload());List<Field> result=new ArrayList<>();
        while(cursor.pos()<compound.end()-1)result.add(cursor.field(0));require(cursor.pos()==compound.end()-1 && cursor.input.readUnsignedByte()==0,"Malformed raw compound");return result;
    }
    private final class Cursor {
        final ByteArrayInputStream bytes;final DataInputStream input;
        Cursor(int offset){bytes=new ByteArrayInputStream(raw,offset,raw.length-offset);input=new DataInputStream(bytes);}
        int pos(){return raw.length-bytes.available();}
        Field field(int depth)throws IOException{int start=pos(),type=input.readUnsignedByte();require(type>=1 && type<=12,"Invalid raw named tag type");String name=input.readUTF();int payload=pos();skip(type,depth);return new Field(type,name,start,payload,pos());}
        void take(long count)throws IOException{require(count>=0 && count<=bytes.available(),"Truncated raw NBT");input.skipNBytes(count);}
        void array(int width)throws IOException{int count=input.readInt();require(count>=0,"Negative raw array length");take((long)count*width);}
        void skip(int type,int depth)throws IOException{
            require(depth<=512,"Raw NBT nesting limit exceeded");
            switch(type){
                case 1->take(1);case 2->take(2);case 3,5->take(4);case 4,6->take(8);
                case 7->array(1);case 8->take(input.readUnsignedShort());
                case 9->{int child=input.readUnsignedByte(),count=input.readInt();require(count>=0 && child<=12 && (child!=0 || count==0) && count<=bytes.available(),"Invalid raw list");for(int i=0;i<count;i++)skip(child,depth+1);}
                case 10->{while(true){int child=input.readUnsignedByte();if(child==0)break;require(child<=12,"Unknown raw tag type");take(input.readUnsignedShort());skip(child,depth+1);}}
                case 11->array(4);case 12->array(8);default->throw new IOException("Unknown raw NBT payload");
            }
        }
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}
