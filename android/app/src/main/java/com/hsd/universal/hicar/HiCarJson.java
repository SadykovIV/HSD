package com.hsd.universal.hicar;
// Strict bounded JSON parsing adapted from huducat586/hicar-android-receiver (MIT).
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
final class HiCarJson {
    static JSONObject object(String s)throws JSONException {
        JSONTokener t=new JSONTokener(s);Object v=value(t,0);
        if(!(v instanceof JSONObject)||t.nextClean()!=0)throw new JSONException("expected object");return (JSONObject)v;
    }
    private static Object value(JSONTokener t,int d)throws JSONException {
        if(d>32)throw new JSONException("depth");char c=t.nextClean();t.back();
        if(c=='{'){
            t.nextClean();JSONObject o=new JSONObject();Set<String> keys=new HashSet<>();
            if(t.nextClean()=='}')return o;t.back();
            while(true){char q=t.nextClean();if(q!='"')throw new JSONException("key");String k=t.nextString(q);
                if(!keys.add(k)||t.nextClean()!=':')throw new JSONException("duplicate or invalid key");o.put(k,value(t,d+1));
                char end=t.nextClean();if(end=='}')return o;if(end!=',')throw new JSONException("object separator");}
        }
        if(c=='['){t.nextClean();JSONArray a=new JSONArray();if(t.nextClean()==']')return a;t.back();
            while(true){a.put(value(t,d+1));char end=t.nextClean();if(end==']')return a;if(end!=',')throw new JSONException("array separator");}}
        if(c=='"'){t.nextClean();return t.nextString('"');}
        Object v=t.nextValue();if(v instanceof String||v instanceof JSONObject||v instanceof JSONArray)throw new JSONException("invalid scalar");return v;
    }

    static JSONObject read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int depth = 0; boolean quoted = false, escape = false;
        while (true) {
            int b = input.read();
            if (b < 0) throw new EOFException("HiCar authentication closed");
            if (bytes.size() == 0) {
                if (b == 32 || b == 10 || b == 13 || b == 9) continue;
                if (b != '{') throw new IOException("HiCar JSON marker");
            }
            bytes.write(b);
            if (bytes.size() > 65536) throw new IOException("HiCar JSON limit");
            if (quoted) { if (escape) escape=false; else if (b=='\\') escape=true; else if(b=='"') quoted=false; }
            else if(b=='"') quoted=true;
            else if(b=='{' || b=='[') { if(++depth>32) throw new IOException("HiCar JSON nesting"); }
            else if(b=='}' || b==']') {
                if(--depth==0) {
                    try { return object(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()); }
                    catch (JSONException e) { throw new IOException("HiCar JSON syntax", e); }
                }
            }
        }
    }
}
