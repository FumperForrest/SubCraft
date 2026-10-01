using System.Collections.Generic;
using SubCraft.Render;
using Xunit;

namespace SubCraft.Tests
{
	public class AlbedoBakeTests
	{
		// A 32x16 atlas: left 16x16 sprite all (200, 100, 50), right sprite all (10, 20, 30).
		private static byte[] Atlas()
		{
			var a = new byte[32 * 16 * 4];
			for (int y = 0; y < 16; y++)
				for (int x = 0; x < 32; x++)
				{
					int o = (y * 32 + x) * 4;
					if (x < 16) { a[o] = 200; a[o + 1] = 100; a[o + 2] = 50; }
					else { a[o] = 10; a[o + 1] = 20; a[o + 2] = 30; }
					a[o + 3] = 255;
				}
			return a;
		}

		/// <summary>One Minecraft quad (capture order q0 q1 q2, q0 q2 q3) over the given UV rectangle.</summary>
		private static void Quad(List<DumpReader.Vertex> into, float u0, float v0, float u1, float v1, uint c0, uint c1, uint c2, uint c3)
		{
			var q = new[]
			{
				new DumpReader.Vertex { U = u0, V = v0, Color = c0 },
				new DumpReader.Vertex { U = u0, V = v1, Color = c1 },
				new DumpReader.Vertex { U = u1, V = v1, Color = c2 },
				new DumpReader.Vertex { U = u1, V = v0, Color = c3 },
			};
			foreach (int i in new[] { 0, 1, 2, 0, 2, 3 }) into.Add(q[i]);
		}

		private static (int r, int g, int b) Texel(AlbedoBake.Result r, float u, float v)
		{
			int x = (int)(u * r.Width), y = (int)(v * r.Height);
			int o = (y * r.Width + x) * 4;
			return (r.Rgba[o], r.Rgba[o + 1], r.Rgba[o + 2]);
		}

		[Fact]
		public void PlainQuadsShareOneCellAndKeepTheirColours()
		{
			var v = new List<DumpReader.Vertex>();
			Quad(v, 0, 0, 0.5f, 1, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
			Quad(v, 0, 0, 0.5f, 1, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
			var r = AlbedoBake.Bake(v, 32, 16, Atlas());
			Assert.Equal(1, r.Cells);
			// Centre of the quad samples the sprite unchanged.
			float u = (r.U[0] + r.U[2]) / 2, w = (r.V[0] + r.V[2]) / 2;
			Assert.Equal((200, 100, 50), Texel(r, u, w));
		}

		[Fact]
		public void TintMultipliesAndAmbientOcclusionFadesAcrossTheQuad()
		{
			var v = new List<DumpReader.Vertex>();
			const uint green = 0xFF00FF00; // r low byte: (0, 255, 0)
			Quad(v, 0.5f, 0, 1, 1, green, green, green, green);                         // tinted right sprite
			Quad(v, 0, 0, 0.5f, 1, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF);     // dark at q0/q1 (u0 side)
			var r = AlbedoBake.Bake(v, 32, 16, Atlas());
			Assert.Equal(2, r.Cells);
			var tinted = Texel(r, (r.U[0] + r.U[2]) / 2, (r.V[0] + r.V[2]) / 2);
			Assert.Equal((0, 20, 0), tinted);
			// AO quad: near q0 (u0) dark, near q3 (u1) full.
			float uA = r.U[6], uB = r.U[6 + 5], vMid = (r.V[6] + r.V[6 + 2]) / 2;
			var dark = Texel(r, uA + 0.5f / r.Width, vMid);
			var lit = Texel(r, uB - 0.5f / r.Width, vMid);
			Assert.True(dark.r < 20, $"dark side {dark}");
			Assert.True(lit.r > 180, $"lit side {lit}");
		}
	}
}
