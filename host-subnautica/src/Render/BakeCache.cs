using System;
using System.Collections.Generic;

namespace SubCraft.Render
{
	/// <summary>
	/// The live world's AlbedoBake (Phase 2): one set of fixed-size texture pages shared by every
	/// section, so a cell (sprite area x four corner colours) is baked once for the whole world: every
	/// plain cobblestone face anywhere uses the same cell. Shelf-packed; cells are never freed (a
	/// new atlas, e.g. a resource reload, clears everything). No Unity types: unit-tested.
	/// </summary>
	public sealed class BakeCache
	{
		public const int PageSize = 1024;

		public sealed class Page
		{
			public readonly byte[] Rgba = new byte[PageSize * PageSize * 4];
			public bool Dirty;
			internal int X, Y, Shelf;
		}

		public readonly List<Page> Pages = new List<Page>();
		private readonly Dictionary<AlbedoBake.Key, (int page, int x, int y)> cells = new Dictionary<AlbedoBake.Key, (int, int, int)>();
		/// <summary>Each cell's source quad (6 vertices), to redo it when its sprite animates.</summary>
		private readonly List<(AlbedoBake.Key key, int page, int x, int y, DumpReader.Vertex[] quad)> sources = new List<(AlbedoBake.Key, int, int, int, DumpReader.Vertex[])>();

		public int Cells => cells.Count;

		/// <summary>Per vertex: page index and UV on that page. Quads are 6 vertices in capture order.</summary>
		public void Bake(IList<DumpReader.Vertex> verts, int atlasW, int atlasH, byte[] atlas, int[] page, float[] u, float[] v)
		{
			int quads = verts.Count / 6;
			for (int q = 0; q < quads; q++)
			{
				var k = AlbedoBake.KeyFor(verts, q * 6, atlasW, atlasH);
				if (!cells.TryGetValue(k, out var cell))
				{
					cell = Allocate(k.X1 - k.X0, k.Y1 - k.Y0);
					var p = Pages[cell.page];
					AlbedoBake.FillCell(k, (cell.x, cell.y), verts, q, atlasW, atlasH, atlas, p.Rgba, PageSize);
					p.Dirty = true;
					cells[k] = cell;
					var quad = new DumpReader.Vertex[6];
					for (int j = 0; j < 6; j++) quad[j] = verts[q * 6 + j];
					sources.Add((k, cell.page, cell.x, cell.y, quad));
				}
				for (int j = 0; j < 6; j++)
				{
					int i = q * 6 + j;
					var vx = verts[i];
					page[i] = cell.page;
					u[i] = (cell.x + (vx.U * atlasW - k.X0)) / PageSize;
					v[i] = (cell.y + (vx.V * atlasH - k.Y0)) / PageSize;
				}
			}
			// A trailing partial quad (shouldn't happen): first page, its own UVs.
			for (int i = quads * 6; i < verts.Count; i++)
			{
				page[i] = 0;
				u[i] = verts[i].U;
				v[i] = verts[i].V;
			}
		}

		private (int page, int x, int y) Allocate(int w, int h)
		{
			w = Math.Min(w, PageSize);
			h = Math.Min(h, PageSize);
			for (int i = Math.Max(0, Pages.Count - 1); i < Pages.Count; i++)
			{
				var p = Pages[i];
				if (p.X + w > PageSize)
				{
					p.X = 0;
					p.Y += p.Shelf;
					p.Shelf = 0;
				}
				if (p.Y + h <= PageSize)
				{
					var at = (i, p.X, p.Y);
					p.X += w;
					p.Shelf = Math.Max(p.Shelf, h);
					return at;
				}
			}
			var np = new Page();
			Pages.Add(np);
			np.X = w;
			np.Shelf = h;
			return (Pages.Count - 1, 0, 0);
		}

		/// <summary>The source texture changed in a rectangle (an animated sprite): redo the cells that use it.</summary>
		public int Refill(int x, int y, int w, int h, int atlasW, int atlasH, byte[] atlas)
		{
			int n = 0;
			foreach (var s in sources)
			{
				var k = s.key;
				if (k.X1 <= x || k.X0 >= x + w || k.Y1 <= y || k.Y0 >= y + h)
				{
					continue;
				}
				var p = Pages[s.page];
				AlbedoBake.FillCell(k, (s.x, s.y), s.quad, 0, atlasW, atlasH, atlas, p.Rgba, PageSize);
				p.Dirty = true;
				n++;
			}
			return n;
		}

		public void Clear()
		{
			Pages.Clear();
			cells.Clear();
			sources.Clear();
		}
	}
}
