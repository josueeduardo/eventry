package io.joshworks.fstore.es.log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class Event {

    private static final Gson gson = new Gson();

    private final String uuid;
    private final String type;
    private final byte[] data;
    private final long timestamp;
    //from index
    private String stream;
    private int version;
    private long position; //TODO experimental

    private Map<String, Object> map;

    private Event(String uuid, String type, byte[] data, long timestamp) {
        this.uuid = uuid;
        this.type = type;
        this.data = data;
        this.timestamp = timestamp;
    }

    static Event load(String uuid, String type, byte[] data, long timestamp) {
        return new Event(uuid, type, data, timestamp);
    }

    public static Event create(String type, String data) {
        return create(type, data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8));
    }

    public static Event create(String type, byte[] data) {
        return new Event(UUID.randomUUID().toString(), type, data, System.currentTimeMillis());
    }

    void stream(String stream) {
        this.stream = stream;
    }

    void version(int version) {
        this.version = version;
    }

    public Map<String, Object> map() {
        if(map == null) {
            Type type  = new TypeToken<Map<String, Object>>(){}.getType();
            map = gson.fromJson(new InputStreamReader(new ByteArrayInputStream(data)), type);
        }
        return map;
    }

    public void position(long position) {
        this.position = position;
    }

    public String stream() {
        return stream;
    }

    public int version() {
        return version;
    }

    public String uuid() {
        return uuid;
    }

    public String type() {
        return type;
    }

    public byte[] data() {
        return data;
    }

    public long timestamp() {
        return timestamp;
    }

    public long position() {
        return position;
    }


    @Override
    public String toString() {
        return "Event{" + "uuid='" + uuid + '\'' +
                ", type='" + type + '\'' +
                ", data='" + data + '\'' +
                ", timestamp=" + timestamp +
                ", stream='" + stream + '\'' +
                ", version=" + version +
                '}';
    }
}
