package dev.diorama.data;

/** Inclusive world-space bounds and the surface/light flags affected by a chunk update. */
public record ChangeBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int flags) {
	public ChangeBounds union(ChangeBounds other) {
		return new ChangeBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
			Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ), flags | other.flags);
	}
}
