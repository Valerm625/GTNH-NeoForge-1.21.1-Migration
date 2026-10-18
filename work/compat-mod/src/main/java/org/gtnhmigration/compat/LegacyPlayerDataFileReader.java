package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;
import net.minecraft.nbt.*;

/** Reads an explicit UUID.dat or level.dat/Data/Player authority, never a
 * backup, temporary file or alternative player mirror as fallback.
 * Caller must exclude concurrent native saves/other writers for the entire
 * recovery operation. Attribute/digest checks detect changes, not an OS-wide
 * transaction lock. No Player, data fixer, load event, or file write is invoked.
 */
public final class LegacyPlayerDataFileReader {
    public enum Authority { PLAYER_FILE, WORLD_PLAYER }
    public static final int MAX_COMPRESSED_BYTES=64*1024*1024;
    public static final int MAX_DECOMPRESSED_BYTES=64*1024*1024;
    private LegacyPlayerDataFileReader() {}
    public static final class Snapshot {
        private final Path directory;
        private final UUID actor;
        private final byte[] bytes;
        private final CompoundTag data;
        private final CompoundTag document;
        private final Authority authority;
        private final String sha256;
        private Snapshot(Path directory,UUID actor,byte[] bytes,CompoundTag data,CompoundTag document,Authority authority) {
            this.directory=directory;this.actor=actor;this.bytes=bytes.clone();this.data=data.copy();
            this.document=document.copy();this.authority=authority;
            this.sha256=LegacyQuestProgressStoreCodec.digest(bytes);
        }
        public Path path(){return directory.resolve(authority==Authority.PLAYER_FILE?actor+".dat":"level.dat");}
        public UUID actor(){return actor;}
        public Authority authority(){return authority;}
        public String sha256(){return sha256;}
        public byte[] originalBytes(){return bytes.clone();}
        public CompoundTag data(){return data.copy();}
        public CompoundTag documentData(){return document.copy();}
        public void verifyUnchanged()throws IOException {
            Snapshot current=read(directory,actor,authority,path->{});
            require(current.sha256.equals(sha256),"Player file changed after snapshot");
        }
    }
    @FunctionalInterface interface ReadObserver {void bytesRead(Path path)throws IOException;}
    public static Snapshot read(Path directory,UUID actor)throws IOException {return read(directory,actor,path->{});}
    /** Explicit level.dat/Data/Player source for an integrated-server owner.
     * Does not fall back to playerdata/UUID.dat or level.dat_old. The caller
     * must establish that this is the native load authority for this player.
     */
    public static Snapshot readWorldPlayer(Path worldDirectory,UUID actor)throws IOException {return read(worldDirectory,actor,Authority.WORLD_PLAYER,path->{});}
    static Snapshot read(Path directory,UUID actor,ReadObserver observer)throws IOException {
        return read(directory,actor,Authority.PLAYER_FILE,observer);
    }
    private static Snapshot read(Path directory,UUID actor,Authority authority,ReadObserver observer)throws IOException {
        Objects.requireNonNull(directory);Objects.requireNonNull(actor);Objects.requireNonNull(observer);
        Path root=directory.toAbsolutePath().normalize();
        BasicFileAttributes rootBefore=attributes(root);
        require(rootBefore.isDirectory() && !rootBefore.isSymbolicLink(),"Player directory is not a real directory");
        Path path=root.resolve(authority==Authority.PLAYER_FILE?actor+".dat":"level.dat");BasicFileAttributes before=attributes(path);
        require(before.isRegularFile() && !before.isSymbolicLink(),"Player file is not a regular file");
        require(before.size()>0 && before.size()<=MAX_COMPRESSED_BYTES,"Player file size is unsupported");
        byte[] raw;
        try(InputStream input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            raw=input.readNBytes(MAX_COMPRESSED_BYTES+1);
        }
        observer.bytesRead(path);
        BasicFileAttributes after=attributes(path),rootAfter=attributes(root);
        require(sameIdentity(rootBefore,rootAfter) && rootAfter.isDirectory() && !rootAfter.isSymbolicLink(),"Player directory changed while reading");
        require(after.isRegularFile() && !after.isSymbolicLink() && sameIdentity(before,after)
            && before.size()==after.size() && raw.length==before.size() && before.lastModifiedTime().equals(after.lastModifiedTime()),"Player file changed while reading");
        byte[] decoded=inflateSingleMember(raw);CompoundTag data;
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(decoded))) {
            data=NbtIo.read(input,NbtAccounter.create(256L*1024*1024));
            require(data!=null && input.read()==-1,"Player file has no single complete NBT root");
        } catch(RuntimeException failure) {throw new IOException("Player NBT cannot be read losslessly",failure);}
        CompoundTag document=data;
        if(authority==Authority.WORLD_PLAYER) {
            require(document.get("Data") instanceof CompoundTag,"World file has no Data compound");
            CompoundTag world=document.getCompound("Data");
            require(world.get("Player") instanceof CompoundTag,"World file has no authoritative Player compound");
            data=world.getCompound("Player");
        }
        require(data.get("UUID") instanceof IntArrayTag && data.getIntArray("UUID").length==4 && data.getUUID("UUID").equals(actor),"Player file UUID does not match expected actor");
        require(data.get("Inventory") instanceof ListTag,"Player inventory list is missing");
        ListTag inventory=(ListTag)data.get("Inventory");
        require(inventory.isEmpty() || inventory.getElementType()==Tag.TAG_COMPOUND,"Player inventory has invalid entry type");
        LegacyRetrievalTransactionJournal.readMarker(data);
        return new Snapshot(root,actor,raw,data,document,authority);
    }
    private static BasicFileAttributes attributes(Path path)throws IOException {return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static boolean sameIdentity(BasicFileAttributes a,BasicFileAttributes b){return Objects.equals(a.fileKey(),b.fileKey()) && a.creationTime().equals(b.creationTime());}

    // GZIPInputStream tolerates trailing junk/concatenated members. Recovery
    // evidence instead requires one validated member and one complete NBT root.
    // Optional standard gzip header fields are parsed without rewriting bytes.
    private static byte[] inflateSingleMember(byte[] raw)throws IOException {
        require(raw.length>=18 && u(raw,0)==31 && u(raw,1)==139 && u(raw,2)==8,"Player file is not gzip/deflate");
        int flags=u(raw,3);require((flags&224)==0,"Unknown gzip flags");int position=10;
        if((flags&4)!=0){require(position+2<=raw.length,"Truncated gzip extra length");int size=u(raw,position)|(u(raw,position+1)<<8);position+=2;require(size<=raw.length-position,"Truncated gzip extra data");position+=size;}
        if((flags&8)!=0)position=skipZero(raw,position);
        if((flags&16)!=0)position=skipZero(raw,position);
        if((flags&2)!=0){require(position+2<=raw.length,"Truncated gzip header checksum");CRC32 crc=new CRC32();crc.update(raw,0,position);require((crc.getValue()&65535)==(u(raw,position)|(u(raw,position+1)<<8)),"Bad gzip header checksum");position+=2;}
        require(position<=raw.length-8,"Missing gzip body/trailer");
        Inflater inflater=new Inflater(true);ByteArrayOutputStream output=new ByteArrayOutputStream();CRC32 crc=new CRC32();
        try {
            inflater.setInput(raw,position,raw.length-position);byte[] block=new byte[8192];
            while(!inflater.finished()) {
                int count=inflater.inflate(block);
                if(count>0){require((long)output.size()+count<=MAX_DECOMPRESSED_BYTES,"Player NBT exceeds supported size; file preserved");output.write(block,0,count);crc.update(block,0,count);}
                else require(inflater.finished(),"Incomplete or unsupported deflate stream");
            }
            int trailer=raw.length-inflater.getRemaining();
            require(raw.length-trailer==8,"Trailing data or multiple gzip members in player file");
            require(little32(raw,trailer)==crc.getValue(),"Bad gzip payload checksum");
            require(little32(raw,trailer+4)==Integer.toUnsignedLong(output.size()),"Bad gzip payload length");
            return output.toByteArray();
        }catch(DataFormatException failure){throw new IOException("Malformed player gzip payload",failure);}
        finally{inflater.end();}
    }
    private static int skipZero(byte[] raw,int start)throws IOException {for(int p=start;p<raw.length;p++)if(raw[p]==0)return p+1;throw new IOException("Unterminated gzip header field");}
    private static int u(byte[] bytes,int offset){return Byte.toUnsignedInt(bytes[offset]);}
    private static long little32(byte[] bytes,int offset){return (long)u(bytes,offset)|((long)u(bytes,offset+1)<<8)|((long)u(bytes,offset+2)<<16)|((long)u(bytes,offset+3)<<24);}
    private static void require(boolean value,String message)throws IOException {if(!value)throw new IOException(message);}
}
