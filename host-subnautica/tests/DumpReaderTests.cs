using System;
using System.Collections.Generic;
using System.IO;
using SubCraft.Link;
using SubCraft.Render;
using Xunit;

namespace SubCraft.Tests
{
	public class DumpReaderTests
	{
		private static byte[] Message(uint type, params byte[][] parts)
		{
			var body = new MemoryStream();
			foreach (var p in parts) body.Write(p, 0, p.Length);
			int payload = (int)body.Length;
			var m = new MemoryStream();
			var w = new BinaryWriter(m);
			w.Write(type);
			w.Write(payload);
			w.Write(body.ToArray());
			while (m.Length % 8 != 0) w.Write((byte)0);
			return m.ToArray();
		}

		private static byte[] Ints(params int[] v)
		{
			var m = new MemoryStream();
			var w = new BinaryWriter(m);
			foreach (int i in v) w.Write(i);
			return m.ToArray();
		}

		private static byte[] Vertex(float x, float y, float z, float u, float v, uint color, uint light, uint flags)
		{
			var m = new MemoryStream();
			var w = new BinaryWriter(m);
			w.Write(x); w.Write(y); w.Write(z); w.Write(u); w.Write(v); w.Write(color); w.Write(light); w.Write(flags);
			return m.ToArray();
		}

		[Fact]
		public void ReadsEveryMessageKind()
		{
			var msgs = new List<byte[]>
			{
				Message(Proto.RenAtlas, Ints(2, 1), new byte[8]),
				Message(Proto.RenTexture, Ints(1, 1, 1, 0), new byte[] { 1, 2, 3, 4 }),
				// Section (1, 4, -1): one triangle at local (1, 2, 3), cutout, emitter, normal up.
				Message(Proto.RenSection, Ints(1, 4, -1, 3), Vertex(1, 2, 3, 0.5f, 0.25f, 0xFF00FF00, 0x0F0E, 1 | 8 | (2 << 4)),
					Vertex(1, 2, 3, 0, 0, 0, 0, 1), Vertex(1, 2, 3, 0, 0, 0, 0, 1)),
				// Lights: one torch at (2, 3, 4) in that section, level 14, flame.
				Message(Proto.RenLights, Ints(1, 4, -1, 1), new byte[] { 2, 3, 4, 14 }, BitConverter.GetBytes(0x01_5EBBDFu)),
			};
			var scene = new MemoryStream();
			var sw = new BinaryWriter(scene);
			sw.Write(100.0); sw.Write(64.0); sw.Write(-5.0); sw.Write(1); sw.Write(3);
			sw.Write(Ints(1, 0, 3, Proto.RenMatCutout));
			for (int i = 0; i < 3; i++) sw.Write(Vertex(0.5f, 0, 0, 0, 0, 0, 0, 1));
			msgs.Add(Message(Proto.RenScene, scene.ToArray()));
			var file = new MemoryStream();
			var fw = new BinaryWriter(file);
			fw.Write(Proto.DumpMagic); fw.Write(Proto.Version); fw.Write((long)msgs.Count);
			foreach (var m in msgs) fw.Write(m);

			var d = DumpReader.Read(file.ToArray());
			Assert.Equal(1, d.Sections);
			Assert.Equal(2, d.Textures[0].Width);
			Assert.Equal(new byte[] { 1, 2, 3, 4 }, d.Textures[1].Rgba);
			var atlas = d.Batches.Find(b => b.Texture == 0);
			var v = atlas.Vertices[0];
			Assert.Equal((17f, 66f, -13f), (v.X, v.Y, v.Z)); // section origin (16, 64, -16) + local
			Assert.Equal(Proto.RenMatCutout, v.Material);
			Assert.True(v.Emitter);
			Assert.Equal(1, v.NormalDir); // Direction.UP
			Assert.Equal(0x0F0Eu, v.Light);
			var light = Assert.Single(d.Lights);
			Assert.Equal((18, 67, -12, 14, 1), (light.X, light.Y, light.Z, light.Level, light.Kind));
			Assert.Equal(0x5EBBDFu, light.Rgb);
			var be = d.Batches.Find(b => b.Texture == 1);
			Assert.Equal(3, be.Vertices.Count);
			Assert.Equal((100.5f, 64f, -5f), (be.Vertices[0].X, be.Vertices[0].Y, be.Vertices[0].Z));
		}
	}
}
