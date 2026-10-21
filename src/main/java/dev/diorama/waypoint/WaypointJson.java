package dev.diorama.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How waypoints are written to {@code waypoints.json}. A banner is one extra field, which older
 * versions of the mod ignore:
 * <pre>"banner": {"base": "black", "layers": [{"pattern": "minecraft:skull", "color": "white"}]}</pre>
 */
public final class WaypointJson {
	/** Ids as their usual text. Shared with the regions file. */
	public static final TypeAdapter<UUID> UUIDS = new TypeAdapter<UUID>() {
		@Override
		public void write(JsonWriter out, UUID value) throws IOException {
			out.value(value.toString());
		}

		@Override
		public UUID read(JsonReader in) throws IOException {
			return UUID.fromString(in.nextString());
		}
	};
	/** Banners as described above. Shared with the regions file. */
	public static final TypeAdapter<BannerDesign> BANNERS = new BannerAdapter().nullSafe();

	static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.registerTypeAdapter(UUID.class, UUIDS)
		.registerTypeAdapter(BannerDesign.class, BANNERS)
		.create();

	private WaypointJson() {
	}

	static @Nullable Waypoint read(JsonElement element) {
		return GSON.fromJson(element, Waypoint.class);
	}

	static String write(List<Waypoint> waypoints) {
		return GSON.toJson(waypoints);
	}

	/**
	 * Dyes by vanilla name, patterns by id. A dye this version doesn't know reads as white, and a
	 * layer missing its pattern is dropped, so a hand-edited file still loads.
	 */
	private static final class BannerAdapter extends TypeAdapter<BannerDesign> {
		@Override
		public void write(JsonWriter out, BannerDesign design) throws IOException {
			out.beginObject();
			out.name("base").value(design.base().id());
			out.name("layers").beginArray();
			for (BannerDesign.Layer layer : design.layers()) {
				out.beginObject();
				out.name("pattern").value(layer.pattern());
				out.name("color").value(layer.color().id());
				out.endObject();
			}
			out.endArray();
			out.endObject();
		}

		@Override
		public BannerDesign read(JsonReader in) throws IOException {
			BannerDesign.Dye base = BannerDesign.Dye.WHITE;
			List<BannerDesign.Layer> layers = new ArrayList<>();
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "base" -> base = dye(in);
					case "layers" -> {
						in.beginArray();
						while (in.hasNext()) {
							String pattern = null;
							BannerDesign.Dye color = BannerDesign.Dye.WHITE;
							in.beginObject();
							while (in.hasNext()) {
								switch (in.nextName()) {
									case "pattern" -> pattern = in.peek() == JsonToken.STRING ? in.nextString() : skip(in);
									case "color" -> color = dye(in);
									default -> in.skipValue();
								}
							}
							in.endObject();
							if (pattern != null && !pattern.isBlank()) {
								layers.add(new BannerDesign.Layer(pattern, color));
							}
						}
						in.endArray();
					}
					default -> in.skipValue();
				}
			}
			in.endObject();
			return new BannerDesign(base, layers);
		}

		private static BannerDesign.Dye dye(JsonReader in) throws IOException {
			if (in.peek() != JsonToken.STRING) {
				in.skipValue();
				return BannerDesign.Dye.WHITE;
			}
			BannerDesign.Dye dye = BannerDesign.Dye.byId(in.nextString());
			return dye == null ? BannerDesign.Dye.WHITE : dye;
		}

		private static @Nullable String skip(JsonReader in) throws IOException {
			in.skipValue();
			return null;
		}
	}
}
