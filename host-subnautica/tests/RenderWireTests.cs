using System;
using System.Runtime.InteropServices;
using SubCraft.Core.Wire;
using SubCraft.Link;
using Xunit;

namespace SubCraft.Tests
{
	/// <summary>Render-ring payload checks: a count the payload can't hold is refused, a valid one passes.</summary>
	public unsafe class RenderWireTests : IDisposable
	{
		private readonly byte* p = (byte*)NativeMemory.AllocZeroed(4096);

		public void Dispose() => NativeMemory.Free(p);

		private void I32(int off, int v) => *(int*)(p + off) = v;

		[Fact]
		public void SectionCountMustFitThePayload()
		{
			I32(12, 3);
			Assert.True(RenderWire.TrySection(p, 16 + 3 * Proto.RenVertexBytes, out int n));
			Assert.Equal(3, n);
			Assert.False(RenderWire.TrySection(p, 16 + 2 * Proto.RenVertexBytes, out _)); // one vertex short
			I32(12, -1);
			Assert.False(RenderWire.TrySection(p, 4096, out _));
			I32(12, int.MaxValue); // count * 32 overflows an int
			Assert.False(RenderWire.TrySection(p, 4096, out _));
			Assert.False(RenderWire.TrySection(p, 15, out _)); // no room for the header
			I32(12, 0);
			Assert.True(RenderWire.TrySection(p, 16, out n)); // 0 vertices = remove the section
			Assert.Equal(0, n);
		}

		[Fact]
		public void LightsAndCollidersCountsMustFit()
		{
			I32(12, 4);
			Assert.True(RenderWire.TryLights(p, 16 + 4 * 8, out _));
			Assert.False(RenderWire.TryLights(p, 16 + 3 * 8, out _));
			Assert.True(RenderWire.TryColliders(p, 16 + 4 * Proto.RenBoxBytes, out _));
			Assert.False(RenderWire.TryColliders(p, 16 + 4 * Proto.RenBoxBytes - 1, out _));
		}

		[Fact]
		public void TexturesMustHoldTheirPixels()
		{
			I32(0, 16); I32(4, 8);
			Assert.True(RenderWire.TryAtlas(p, 8 + 16 * 8 * 4, out int w, out int h));
			Assert.Equal((16, 8), (w, h));
			Assert.False(RenderWire.TryAtlas(p, 8 + 16 * 8 * 4 - 1, out _, out _));
			I32(0, 65536); I32(4, 65536); // w * h * 4 overflows
			Assert.False(RenderWire.TryAtlas(p, int.MaxValue, out _, out _));
			I32(0, 0);
			Assert.False(RenderWire.TryAtlas(p, 4096, out _, out _));

			I32(0, 7); I32(4, 4); I32(8, 4);
			Assert.True(RenderWire.TryTexture(p, 16 + 64, out int id, out _, out _));
			Assert.Equal(7, id);
			Assert.False(RenderWire.TryTexture(p, 16 + 63, out _, out _, out _));

			I32(0, 5); I32(4, 6); I32(8, 2); I32(12, 3);
			Assert.True(RenderWire.TryAtlasRegion(p, 16 + 24, out _, out _, out _, out _));
			Assert.False(RenderWire.TryAtlasRegion(p, 16 + 23, out _, out _, out _, out _));
			I32(8, -2);
			Assert.False(RenderWire.TryAtlasRegion(p, 4096, out _, out _, out _, out _));
			I32(8, 2); I32(0, -1);
			Assert.False(RenderWire.TryAtlasRegion(p, 4096, out _, out _, out _, out _));
		}

		[Fact]
		public void SoundMustHoldItsFile()
		{
			I32(0, 9); I32(4, 100);
			Assert.True(RenderWire.TrySound(p, Proto.RenSoundBytes + 100, out uint id, out int len));
			Assert.Equal((9u, 100), (id, len));
			Assert.False(RenderWire.TrySound(p, Proto.RenSoundBytes + 99, out _, out _));
			I32(4, -5);
			Assert.False(RenderWire.TrySound(p, 4096, out _, out _));
		}

		[Fact]
		public void SceneBatchesMustLieInsideItsVertices()
		{
			I32(24, 2); I32(28, 6); // two batches, six vertices
			I32(32 + 4, 0); I32(32 + 8, 3);
			I32(48 + 4, 3); I32(48 + 8, 3);
			int bytes = 32 + 2 * Proto.RenBatchBytes + 6 * Proto.RenVertexBytes;
			Assert.True(RenderWire.TryScene(p, bytes, out int bc, out int vc));
			Assert.Equal((2, 6), (bc, vc));
			Assert.False(RenderWire.TryScene(p, bytes - 1, out _, out _));
			I32(48 + 8, 4); // second batch runs one vertex past the end
			Assert.False(RenderWire.TryScene(p, bytes, out _, out _));
			I32(48 + 8, 3); I32(48 + 4, -1);
			Assert.False(RenderWire.TryScene(p, bytes, out _, out _));
			I32(48 + 4, 3); I32(24, -1);
			Assert.False(RenderWire.TryScene(p, bytes, out _, out _));
		}

		[Fact]
		public void SubLevelPlotBoxMustBeSane()
		{
			int[] box = { -2, 0, 5, 1, 2, 5 }; // min x y z, max x y z
			for (int i = 0; i < 6; i++) I32(8 + i * 4, box[i]);
			Assert.True(RenderWire.TrySubLevel(p, Proto.RenSubLevelBytes));
			Assert.False(RenderWire.TrySubLevel(p, Proto.RenSubLevelBytes - 1));
			I32(8 + 3 * 4, -3); // max x below min x
			Assert.False(RenderWire.TrySubLevel(p, Proto.RenSubLevelBytes));
			I32(8 + 3 * 4, int.MaxValue); // a box the host would loop over forever
			Assert.False(RenderWire.TrySubLevel(p, Proto.RenSubLevelBytes));
			I32(4, 1); // "gone": the box doesn't matter
			Assert.True(RenderWire.TrySubLevel(p, Proto.RenSubLevelBytes));
		}
	}
}
