package com.afterlife.rp.shared.missions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.UUID;

/** A persisted mission state machine row (rule 8). */
public record Mission(
        UUID id,
        String type,
        UUID owner,
        String state,
        UUID targetPoiId,
        UUID originPoiId,
        long rewardSnapshot,
        String data,
        int version) {

    private static final Gson GSON = new Gson();

    public JsonObject dataJson() {
        JsonObject json = data == null ? null : GSON.fromJson(data, JsonObject.class);
        return json == null ? new JsonObject() : json;
    }

    public String dataString(String key) {
        JsonObject json = dataJson();
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    public long dataLong(String key, long fallback) {
        JsonObject json = dataJson();
        return json.has(key) ? json.get(key).getAsLong() : fallback;
    }
}
