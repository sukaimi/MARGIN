package ai.codeandcraft.secondbrain;
import java.io.*;import java.nio.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.Locale;
/** Streaming PCM header/hash validation for evidence pointers. Does not load ASR samples. */
public final class LocalAudioReference {
    private LocalAudioReference(){}
    public static final class Source {public final String sha256;public final long durationMs;Source(String sha,long duration){sha256=sha;durationMs=duration;}}
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    private static long u32(byte[] b,int offset){return Integer.toUnsignedLong(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(offset));}
    public static Source inspect(File file)throws Exception{
        require(file.isFile()&&file.length()>=44&&file.length()<=4194304,"Missing or oversized audio reference");long length=file.length(),payload=-1;boolean format=false;
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            byte[] header=new byte[12];input.readFully(header);require("RIFF".equals(new String(header,0,4,StandardCharsets.US_ASCII))&&"WAVE".equals(new String(header,8,4,StandardCharsets.US_ASCII)),"Invalid WAV reference");
            long end=8+u32(header,4);require(end==length,"WAV reference length mismatch");long position=12;
            while(position+8<=end){input.seek(position);byte[] chunk=new byte[8];input.readFully(chunk);String name=new String(chunk,0,4,StandardCharsets.US_ASCII);long size=u32(chunk,4),data=position+8;require(size<=end-data,"WAV chunk out of bounds");
                if("fmt ".equals(name)){require(!format&&size>=16,"Duplicate/invalid WAV format");byte[] bytes=new byte[16];input.readFully(bytes);ByteBuffer fmt=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);require(fmt.getShort(0)==1&&fmt.getShort(2)==1&&fmt.getInt(4)==16000&&fmt.getInt(8)==32000&&fmt.getShort(12)==2&&fmt.getShort(14)==16,"Expected PCM16 mono16k audio reference");format=true;}
                if("data".equals(name)){require(payload<0&&size>0&&size%2==0,"Duplicate/invalid WAV data");payload=size;}
                position=data+size+(size%2);require(position<=end,"WAV chunk padding mismatch");
            }
            require(position==end&&format&&payload>0&&payload<=3840000,"Invalid audio reference, limit120seconds");
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream input=new FileInputStream(file)){byte[] bytes=new byte[8192];int count;long total=0;while((count=input.read(bytes))!=-1){total+=count;require(total<=4194304,"Audio changed/oversized during validation");digest.update(bytes,0,count);}require(total==length,"Audio changed during validation");}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));return new Source(sha.toString(),payload/32L);
    }
}
