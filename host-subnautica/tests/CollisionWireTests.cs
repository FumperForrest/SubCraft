using System;
using System.Collections.Generic;
using System.IO;
using SubCraft.Core.Wire;
using SubCraft.Link;
using Xunit;

namespace SubCraft.Tests
{
	/// <summary>
	/// The host's collision payloads against protocol/fixtures/*.bin, the bytes Minecraft's parser is
	/// tested on (guest CollisionWireTest). Set SUBCRAFT_WRITE_FIXTURES=1 to regenerate them after a
	/// deliberate protocol change, then run both test suites.
	/// </summary>
	public class CollisionWireTests
	{
		internal static readonly float[] FixtureTris =
		{
			32.5f, -12f, -48.25f, 33.5f, -12f, -48.25f, 32.5f, -11f, -47.25f,
			40f, -16f, -40f, 41f, -15.5f, -40f, 40f, -15.5f, -39f,
		};

		internal static readonly uint[] FixtureFlags =
		{
			Proto.TriTerrain | (uint)Proto.MatSand << Proto.TriMaterialShift,
			Proto.TriStructure | (uint)Proto.MatMetal << Proto.TriMaterialShift,
		};

		internal static readonly string[] FixtureBiomes = { "kelpForest", "safeShallows", "grassyPlateaus_Cave" };

		private static byte[] FixtureCells()
		{
			var cells = new byte[Proto.BiomeCells];
			for (int i = 0; i < cells.Length; i++)
			{
				cells[i] = i % 4 == 3 ? Proto.BiomeUnknown : (byte)(i % 4); // (y * 4 + z) * 4 + x order
			}
			return cells;
		}

		private static void Fixture(string name, byte[] bytes)
		{
			string dir = Path.Combine(Path.GetDirectoryName(ProtoLayoutTests.FindRepoFile("protocol/layout.json")), "fixtures");
			string path = Path.Combine(dir, name);
			if (Environment.GetEnvironmentVariable("SUBCRAFT_WRITE_FIXTURES") == "1")
			{
				File.WriteAllBytes(path, bytes);
			}
			Assert.True(File.Exists(path), $"{path} missing: run with SUBCRAFT_WRITE_FIXTURES=1");
			Assert.Equal(File.ReadAllBytes(path), bytes);
		}

		[Fact]
		public void TrianglesMatchTheFixture()
		{
			var payload = CollisionWire.Tris(2, -1, -3, 0x12345679u, FixtureTris, FixtureFlags);
			Assert.Equal(Proto.ColRegionBytes + 2 * Proto.ColTriBytes, payload.Length);
			Assert.Equal(32, BitConverter.ToInt32(payload, 0));            // minX = sx * 16
			Assert.Equal(-1 * 16 + 15, BitConverter.ToInt32(payload, 16)); // maxY
			Assert.Equal(2, BitConverter.ToInt32(payload, 28));             // count
			Fixture("col_tris.bin", payload);
		}

		[Fact]
		public void EmptySectionIsJustTheBox()
		{
			var payload = CollisionWire.Tris(0, 0, 0, 7, Array.Empty<float>(), Array.Empty<uint>());
			Assert.Equal(Proto.ColRegionBytes, payload.Length);
			Assert.Equal(0, BitConverter.ToInt32(payload, 28));
		}

		[Fact]
		public void BiomesMatchTheFixture()
		{
			var payload = CollisionWire.Biomes(2, -1, -3, FixtureCells(), FixtureBiomes);
			Assert.Equal(3, payload[12]);
			Fixture("col_biomes.bin", payload);
		}

		[Fact]
		public void BiomeNamesAreCappedAt254()
		{
			var names = new List<string>();
			for (int i = 0; i < 300; i++) names.Add("b" + i);
			var payload = CollisionWire.Biomes(0, 0, 0, FixtureCells(), names);
			Assert.Equal(254, payload[12]);
		}
	}
}
