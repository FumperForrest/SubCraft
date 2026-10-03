using System.Collections.Generic;
using SubCraft.Render;
using Xunit;

namespace SubCraft.Tests
{
	public class BakeCacheTests
	{
		private static byte[] Atlas()
		{
			var a = new byte[32 * 16 * 4];
			for (int i = 0; i < a.Length; i += 4)
			{
				a[i] = 200; a[i + 1] = 100; a[i + 2] = 50; a[i + 3] = 255;
			}
			return a;
		}

		private static void Quad(List<DumpReader.Vertex> into, float u0, float v0, float u1, float v1, uint c)
		{
			var q = new[]
			{
				new DumpReader.Vertex { U = u0, V = v0, Color = c }, new DumpReader.Vertex { U = u0, V = v1, Color = c },
				new DumpReader.Vertex { U = u1, V = v1, Color = c }, new DumpReader.Vertex { U = u1, V = v0, Color = c },
			};
			foreach (int i in new[] { 0, 1, 2, 0, 2, 3 }) into.Add(q[i]);
		}

		private static (int[] page, float[] u, float[] v) Run(BakeCache cache, List<DumpReader.Vertex> verts)
		{
			var p = new int[verts.Count];
			var u = new float[verts.Count];
			var v = new float[verts.Count];
			cache.Bake(verts, 32, 16, Atlas(), p, u, v);
			return (p, u, v);
		}

		[Fact]
		public void SameFaceInTwoSectionsSharesOneCell()
		{
			var cache = new BakeCache();
			var a = new List<DumpReader.Vertex>();
			Quad(a, 0, 0, 0.5f, 1, 0xFFFFFFFF);
			var b = new List<DumpReader.Vertex>();
			Quad(b, 0, 0, 0.5f, 1, 0xFFFFFFFF);
			var ra = Run(cache, a);
			var rb = Run(cache, b);
			Assert.Equal(1, cache.Cells);
			Assert.Equal(ra.u, rb.u);
			Assert.Single(cache.Pages);
			Assert.True(cache.Pages[0].Dirty);
		}

		[Fact]
		public void TintedFaceGetsItsOwnColouredCell()
		{
			var cache = new BakeCache();
			var a = new List<DumpReader.Vertex>();
			Quad(a, 0, 0, 0.5f, 1, 0xFFFFFFFF);
			Quad(a, 0, 0, 0.5f, 1, 0xFF808080); // half grey: AO or tint
			var r = Run(cache, a);
			Assert.Equal(2, cache.Cells);
			// The second cell's texels are the atlas colour times ~0.5.
			var page = cache.Pages[r.page[6]];
			int x = (int)(r.u[6] * BakeCache.PageSize + 0.5f), y = (int)(r.v[6] * BakeCache.PageSize + 0.5f);
			int o = (y * BakeCache.PageSize + x) * 4;
			Assert.InRange(page.Rgba[o], 96, 104);
		}

		[Fact]
		public void FullPageSpillsToANewPage()
		{
			var cache = new BakeCache();
			var verts = new List<DumpReader.Vertex>();
			// 64 x 64 distinct 16x16 cells = one full 1024 page, plus one more.
			for (int i = 0; i <= 64 * 64; i++)
			{
				Quad(verts, 0, 0, 0.5f, 1, 0xFF000000u | (uint)(i * 4 % 256) | (uint)(i / 64 * 4 % 256) << 8 | (uint)(i / 4096 * 4) << 16);
			}
			var r = Run(cache, verts);
			Assert.Equal(64 * 64 + 1, cache.Cells);
			Assert.Equal(2, cache.Pages.Count);
			Assert.Equal(1, r.page[r.page.Length - 1]);
		}

		[Fact]
		public void AnimatedSpriteRefillsItsCells()
		{
			var cache = new BakeCache();
			var verts = new List<DumpReader.Vertex>();
			Quad(verts, 0, 0, 0.5f, 1, 0xFFFFFFFF);  // left sprite (x 0..16)
			Quad(verts, 0.5f, 0, 1, 1, 0xFFFFFFFF);  // right sprite (x 16..32)
			var atlas = Atlas();
			var p = new int[verts.Count];
			var u = new float[verts.Count];
			var v = new float[verts.Count];
			cache.Bake(verts, 32, 16, atlas, p, u, v);
			// The left sprite's next frame is blue.
			for (int y = 0; y < 16; y++)
				for (int x = 0; x < 16; x++)
				{
					int o = (y * 32 + x) * 4;
					atlas[o] = 0; atlas[o + 1] = 0; atlas[o + 2] = 255;
				}
			cache.Pages[0].Dirty = false;
			Assert.Equal(1, cache.Refill(0, 0, 16, 16, 32, 16, atlas));
			Assert.True(cache.Pages[0].Dirty);
			int px = (int)(u[0] * BakeCache.PageSize + 0.5f), py = (int)(v[0] * BakeCache.PageSize + 0.5f);
			int at = (py * BakeCache.PageSize + px) * 4;
			Assert.Equal(255, cache.Pages[0].Rgba[at + 2]);
			Assert.Equal(0, cache.Pages[0].Rgba[at]);
		}

		[Fact]
		public void QuadLargerThanAPageStaysInsideItsCell()
		{
			// Tiled UVs (a beam, a modded quad repeating its sprite) span far more texels than a page.
			var cache = new BakeCache();
			var first = new List<DumpReader.Vertex>();
			Quad(first, 0, 0, 0.5f, 1, 0xFF808080);
			Run(cache, first);
			var before = (byte[])cache.Pages[0].Rgba.Clone();
			var huge = new List<DumpReader.Vertex>();
			Quad(huge, 0, 0, 40f, 70f, 0xFF404040); // 1280 x 1120 texels of a 32x16 atlas
			for (int frame = 0; frame < 3; frame++)
			{
				Run(cache, huge); // used to throw IndexOutOfRange after scribbling over other cells
			}
			Assert.Equal(2, cache.Cells);
			Assert.Equal(2, cache.Pages.Count); // the first cell's page, and one for the huge cell
			// The first cell (16x16 at the page origin) is untouched.
			for (int y = 0; y < 16; y++)
			{
				for (int x = 0; x < 16 * 4; x++)
				{
					Assert.Equal(before[y * BakeCache.PageSize * 4 + x], cache.Pages[0].Rgba[y * BakeCache.PageSize * 4 + x]);
				}
			}
		}
	}
}
