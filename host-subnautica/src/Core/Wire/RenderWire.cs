namespace SubCraft.Core.Wire
{
	/// <summary>
	/// Bounds checks for render-ring payloads (protocol/subcraft_protocol.h, render ring), before
	/// the host reads them through raw pointers. Minecraft is another process: a count or size it
	/// wrote is only trusted once it fits inside the payload it came with. Every check here returns
	/// false for a payload that would make a reader go past its end, so the caller can drop the
	/// message instead of reading other memory (or past the 191 MiB mapping and crashing).
	///
	/// Unity-free (src/Core): compiled into the no-game tests.
	/// </summary>
	public static unsafe class RenderWire
	{
		/// <summary>Largest texture side accepted (Minecraft's own limit is far below).</summary>
		public const int MaxTextureSide = 16384;

		/// <summary>
		/// Largest sub-level plot extent in sections per axis (2048 blocks; a Sable plot is a few
		/// sections). The host walks every section of the box on each pose message, so a garbage box
		/// would otherwise hang the frame.
		/// </summary>
		public const int MaxSubLevelSections = 128;

		/// <summary>
		/// A header of <paramref name="headerBytes"/> whose last int is a count, followed by count
		/// items of <paramref name="itemBytes"/> (kRenSection, kRenLights, kRenColliders).
		/// </summary>
		public static bool TryCounted(byte* p, int bytes, int headerBytes, int itemBytes, out int count)
		{
			count = 0;
			if (bytes < headerBytes)
			{
				return false;
			}
			count = *(int*)(p + headerBytes - 4);
			return count >= 0 && headerBytes + (long)count * itemBytes <= bytes;
		}

		/// <summary>kRenSection: RenSection + RenVertex[vertexCount] (a triangle list).</summary>
		public static bool TrySection(byte* p, int bytes, out int vertexCount) =>
			TryCounted(p, bytes, 16, Link.Proto.RenVertexBytes, out vertexCount);

		/// <summary>kRenLights: RenLights + RenLight[count].</summary>
		public static bool TryLights(byte* p, int bytes, out int count) => TryCounted(p, bytes, 16, 8, out count);

		/// <summary>kRenColliders: RenColliders + RenBox[count].</summary>
		public static bool TryColliders(byte* p, int bytes, out int count) =>
			TryCounted(p, bytes, Link.Proto.RenCollidersBytes, Link.Proto.RenBoxBytes, out count);

		/// <summary>w x h RGBA8 pixels after a header of <paramref name="headerBytes"/>.</summary>
		private static bool PixelsFit(int headerBytes, int w, int h, int bytes, bool allowEmpty) =>
			w >= (allowEmpty ? 0 : 1) && h >= (allowEmpty ? 0 : 1) && w <= MaxTextureSide && h <= MaxTextureSide
			&& headerBytes + (long)w * h * 4 <= bytes;

		/// <summary>kRenAtlas: RenAtlas (w, h) + pixels.</summary>
		public static bool TryAtlas(byte* p, int bytes, out int w, out int h)
		{
			w = h = 0;
			if (bytes < 8)
			{
				return false;
			}
			w = *(int*)p;
			h = *(int*)(p + 4);
			return PixelsFit(8, w, h, bytes, false);
		}

		/// <summary>kRenTexture: RenTexture (id, w, h, pad) + pixels.</summary>
		public static bool TryTexture(byte* p, int bytes, out int id, out int w, out int h)
		{
			id = w = h = 0;
			if (bytes < 16)
			{
				return false;
			}
			id = *(int*)p;
			w = *(int*)(p + 4);
			h = *(int*)(p + 8);
			return PixelsFit(16, w, h, bytes, false);
		}

		/// <summary>kRenAtlasRegion: RenAtlasRegion (x, y, w, h) + pixels. The caller still checks the rectangle against its atlas.</summary>
		public static bool TryAtlasRegion(byte* p, int bytes, out int x, out int y, out int w, out int h)
		{
			x = y = w = h = 0;
			if (bytes < 16)
			{
				return false;
			}
			x = *(int*)p;
			y = *(int*)(p + 4);
			w = *(int*)(p + 8);
			h = *(int*)(p + 12);
			return x >= 0 && y >= 0 && PixelsFit(16, w, h, bytes, true);
		}

		/// <summary>kRenSound: RenSound (id, bytes, pad) + the file.</summary>
		public static bool TrySound(byte* p, int bytes, out uint id, out int fileBytes)
		{
			id = 0;
			fileBytes = 0;
			if (bytes < Link.Proto.RenSoundBytes)
			{
				return false;
			}
			id = *(uint*)p;
			fileBytes = *(int*)(p + 4);
			return fileBytes >= 0 && Link.Proto.RenSoundBytes + (long)fileBytes <= bytes;
		}

		/// <summary>
		/// kRenSubLevel: the fixed-size record, a plot box that is not inverted (unless the record
		/// says "gone") and not absurdly large (the host loops over every section in it each pose).
		/// </summary>
		public static bool TrySubLevel(byte* p, int bytes)
		{
			if (bytes < Link.Proto.RenSubLevelBytes)
			{
				return false;
			}
			if ((*(uint*)(p + 4) & 1) != 0)
			{
				return true;
			}
			int* r = (int*)(p + 8);
			for (int axis = 0; axis < 3; axis++)
			{
				long extent = (long)r[axis + 3] - r[axis];
				if (extent < 0 || extent >= MaxSubLevelSections)
				{
					return false;
				}
			}
			return true;
		}

		/// <summary>
		/// kRenScene / kRenHand: RenScene (origin, batchCount, vertexCount) + RenBatch[batchCount] +
		/// RenVertex[vertexCount], every batch inside the vertices.
		/// </summary>
		public static bool TryScene(byte* p, int bytes, out int batchCount, out int vertexCount)
		{
			batchCount = vertexCount = 0;
			if (bytes < 32)
			{
				return false;
			}
			batchCount = *(int*)(p + 24);
			vertexCount = *(int*)(p + 28);
			if (batchCount < 0 || vertexCount < 0
				|| 32 + (long)batchCount * Link.Proto.RenBatchBytes + (long)vertexCount * Link.Proto.RenVertexBytes > bytes)
			{
				return false;
			}
			for (int i = 0; i < batchCount; i++)
			{
				byte* b = p + 32 + i * Link.Proto.RenBatchBytes;
				int first = *(int*)(b + 4), count = *(int*)(b + 8);
				if (first < 0 || count < 0 || (long)first + count > vertexCount)
				{
					return false;
				}
			}
			return true;
		}
	}
}
