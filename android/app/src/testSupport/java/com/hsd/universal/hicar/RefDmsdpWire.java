package com.hsd.universal.hicar;
// Independent MIT receiver fixture, pinned a93b36ff5c705d4825c3c5a8d6fdb6fa605739ce.
// Production code must not depend on this receiver implementation.
import java.io.*;
import java.util.*;

/** CarLink dmsdp_wire.py (MIT): module frame, separate from CCMP. */
final class RefDmsdpWire {
    static final class Packet {final int module;final byte[] payload;Packet(int m,byte[] p){module=m;payload=p;}}
    static byte[] pack(int module,byte[] payload){
        if(payload.length>65536)throw new IllegalArgumentException("DMSDP size");
        byte[] b=RefNearbyWire.join(new byte[]{127},RefNearbyWire.uint(module),RefNearbyWire.uint(payload.length),payload);
        int c=RefNearbyWire.crc(b,b.length);return RefNearbyWire.join(b,new byte[]{(byte)c,(byte)(c>>>8)});
    }
    static final class Decoder {
        byte[] buffer=new byte[0];boolean failed;
        List<Packet> feed(byte[] b,int n)throws IOException {
            if(failed||n<0||n>4096||n>b.length)throw new IOException("DMSDP decoder state");
            buffer=RefNearbyWire.join(buffer,Arrays.copyOf(b,n));List<Packet> out=new ArrayList<>();
            try{while(buffer.length>0){
                try{
                    if(buffer[0]!=127)throw new IOException("DMSDP marker");RefNearbyWire.Cursor c=new RefNearbyWire.Cursor(buffer,1);
                    int module=c.uint();byte[] payload=c.take(c.uint()),sum=c.take(2);
                    if(RefNearbyWire.crc(buffer,c.p-2)!=((sum[0]&255)|((sum[1]&255)<<8)))throw new IOException("DMSDP CRC");
                    out.add(new Packet(module,payload));buffer=Arrays.copyOfRange(buffer,c.p,buffer.length);
                }catch(RefNearbyWire.Short e){break;}
            }
            if(buffer.length>65547)throw new IOException("DMSDP buffer");
            }catch(IOException e){failed=true;buffer=new byte[0];throw e;}return out;
        }
    }
    static byte[] controlOffer(Packet p,int port)throws IOException {
        if(p.module!=1||!Arrays.equals(p.payload,new byte[16])||port<1024||port>65535)throw new IOException("DMSDP request");
        byte[] reply=new byte[16];reply[1]=(byte)(port>>>8);reply[2]=(byte)port;return pack(1,reply);
    }
}
