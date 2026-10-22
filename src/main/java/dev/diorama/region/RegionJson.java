package dev.diorama.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.WaypointJson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How regions are written to {@code regions.json}: a version and the regions, each ring a flat
 * list of x, z and height per vertex, dyes by vanilla name, banners as in {@code waypoints.json}:
 * <pre>{"version": 1, "regions": [{"name": "Home", "dye": "red", "rings": [[0, 0, 64, 16, 0, 64, 16, 16, 70]], ...}]}</pre>
 * A file from a newer version is read but never written over; see {@link RegionStore}.
 */
final class RegionJson {
	/** The format this version writes. */
	static final int VERSION = 1;

	/** Compact: printed prettily, every number of a ring would take a line. */
	static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(UUID.class, WaypointJson.UUIDS)
		.registerTypeAdapter(BannerDesign.class, WaypointJson.BANNERS)
		.registerTypeAdapter(BannerDesign.Dye.class, new DyeAdapter().nullSafe())
		.registerTypeAdapter(Ring.class, new RingAdapter().nullSafe())
		.create();

	private RegionJson() {
	}

	/** What a file held: its version, the regions that could be read, and whether any couldn't. */
	record Contents(int version, List<MapRegion> regions, boolean damaged) {
	}

	/** Reads a file's root one region at a time, so a single bad entry costs only itself. */
	static Contents read(JsonElement root) {
		if (!root.isJsonObject()) {
			return new Contents(VERSION, List.of(), true);
		}
		JsonObject object = root.getAsJsonObject();
		int version = VERSION;
		boolean damaged = false;
		try {
			version = object.has("version") ? object.get("version").getAsInt() : VERSION;
		} catch (RuntimeException e) {
			damaged = true;
		}
		List<MapRegion> regions = new ArrayList<>();
		JsonElement list = object.get("regions");
		if (list == null || !list.isJsonArray()) {
			return new Contents(version, regions, damaged || list != null);
		}
		for (JsonElement element : list.getAsJsonArray()) {
			try {
				MapRegion region = normalise(GSON.fromJson(element, MapRegion.class));
				if (region == null) {
					damaged = true;
				} else {
					regions.add(region);
				}
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException | UnsupportedOperationException e) {
				damaged = true;
			}
		}
		return new Contents(version, regions, damaged);
	}

	static String write(List<MapRegion> regions) {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		JsonArray list = new JsonArray();
		for (MapRegion region : regions) {
			list.add(GSON.toJsonTree(region));
		}
		root.add("regions", list);
		return GSON.toJson(root);
	}

	/** Fills in anything a hand-edited or older file left out; null if the region is unusable. */
	static @Nullable MapRegion normalise(@Nullable MapRegion region) {
		if (region == null || region.dimension == null || region.rings == null) {
			return null;
		}
		region.rings = new ArrayList<>(region.rings.stream().filter(r -> r != null && r.size() >= 3).toList());
		if (region.rings.isEmpty()) {
			return null;
		}
		if (region.id == null) region.id = UUID.randomUUID();
		if (region.name == null) region.name = "";
		if (region.dye == null) region.dye = BannerDesign.Dye.RED;
		if (region.fill == null) region.fill = MapRegion.Fill.NORMAL;
		region.reshaped();
		return region;
	}

	/** Dyes by vanilla name; one this version doesn't know reads as null, then red. */
	private static final class DyeAdapter extends TypeAdapter<BannerDesign.Dye> {
		@Override
		public void write(JsonWriter out, BannerDesign.Dye dye) throws IOException {
			out.value(dye.id());
		}

		@Override
		public BannerDesign.@Nullable Dye read(JsonReader in) throws IOException {
			if (in.peek() != JsonToken.STRING) {
				in.skipValue();
				return null;
			}
			return BannerDesign.Dye.byId(in.nextString());
		}
	}

	/** A ring as x, z, height for each vertex in turn; a list whose length isn't a multiple of three is unusable. */
	private static final class RingAdapter extends TypeAdapter<Ring> {
		@Override
		public void write(JsonWriter out, Ring ring) throws IOException {
			out.beginArray();
			for (int i = 0; i < ring.size(); i++) {
				number(out, ring.x(i));
				number(out, ring.z(i));
				out.value(ring.y(i));
			}
			out.endArray();
		}

		/** Whole blocks without a ".0", the half blocks outlines are kept to with one. */
		private static void number(JsonWriter out, float value) throws IOException {
			if (value == Math.rint(value)) {
				out.value((long) value);
			} else {
				out.value((double) value);
			}
		}

		@Override
		public Ring read(JsonReader in) throws IOException {
			List<Double> values = new ArrayList<>();
			in.beginArray();
			while (in.hasNext()) {
				values.add(in.nextDouble());
			}
			in.endArray();
			if (values.size() % 3 != 0) {
				throw new JsonParseException("A ring needs x, z and a height for each vertex");
			}
			int n = values.size() / 3;
			float[] x = new float[n], z = new float[n];
			short[] y = new short[n];
			for (int i = 0; i < n; i++) {
				x[i] = values.get(3 * i).floatValue();
				z[i] = values.get(3 * i + 1).floatValue();
				y[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(values.get(3 * i + 2))));
			}
			return new Ring(x, z, y);
		}
	}
}
