import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload;
import io.netty.buffer.Unpooled;
public class LongFuelRoundTrip {
  public static void main(String[] args) throws Exception {
    int[] edges={0,1,16000,32767,32768,65535,65536,102400,144000,288000,1024000,1296000,2592000,11664000,23328000,104976000,209952000,944784000,1889568000,Integer.MAX_VALUE};
    ArrayList<Integer> values=new ArrayList<>();for(int v:edges) values.add(v);
    Random random=new Random(1721211);for(int i=0;i<100;i++)values.add(random.nextInt(Integer.MAX_VALUE));
    int count=0, shortFailures=0;
    for(int ticks:values){
      CompoundTag original=new CompoundTag();original.putInt("BurnTime",ticks);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(DataOutputStream output=new DataOutputStream(bytes)){NbtIo.write(original,output);}
      CompoundTag decoded;
      try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){decoded=NbtIo.read(input,NbtAccounter.unlimitedHeap());}
      if(!decoded.contains("BurnTime",Tag.TAG_INT)||decoded.getInt("BurnTime")!=ticks)throw new AssertionError("NBT truncation "+ticks);
      RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
      try{
        AdvancedContainerSetDataPayload sent=new AdvancedContainerSetDataPayload((byte)1,(short)0,ticks);
        AdvancedContainerSetDataPayload.STREAM_CODEC.encode(buf,sent);
        AdvancedContainerSetDataPayload received=AdvancedContainerSetDataPayload.STREAM_CODEC.decode(buf);
        if(received.value()!=ticks||received.containerId()!=1||received.dataId()!=0||buf.readableBytes()!=0)throw new AssertionError("Payload truncation "+ticks);
      }finally{buf.release();}
      if((short)ticks!=ticks)shortFailures++;
      count++;
    }
    System.out.println("{\"test\":\"actual_minecraft_nbt_and_neoforge_payload_roundtrip\",\"passed\":true,\"cases\":"+count+",\"values_truncated_by_short_control\":"+shortFailures+",\"max_tested\":"+Integer.MAX_VALUE+",\"scope\":\"Real binary codecs only; no server/world, reload or connected client was started.\"}");
  }
}
