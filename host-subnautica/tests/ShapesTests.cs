using SubCraft.Core.Collision;
using Xunit;

namespace SubCraft.Tests
{
	/// <summary>Collider tessellation winding: the guest voxelizer fills by it, so every face must point out.</summary>
	public class ShapesTests
	{
		/// <summary>(b - a) x (c - a) . (centroid - centre): positive when the triangle faces away from the centre.</summary>
		private static double Outwardness(float[] p, int a, int b, int c, double cx, double cy, double cz)
		{
			double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
			double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double mx = (p[a * 3] + p[b * 3] + p[c * 3]) / 3 - cx;
			double my = (p[a * 3 + 1] + p[b * 3 + 1] + p[c * 3 + 1]) / 3 - cy;
			double mz = (p[a * 3 + 2] + p[b * 3 + 2] + p[c * 3 + 2]) / 3 - cz;
			return nx * mx + ny * my + nz * mz;
		}

		[Fact]
		public void EveryBoxFaceFacesOut()
		{
			var p = new float[24];
			for (int i = 0; i < 8; i++)
			{
				Shapes.BoxCorner(i, 1, 2, 3, 0.5f, 1.5f, 2f, out p[i * 3], out p[i * 3 + 1], out p[i * 3 + 2]);
			}
			Assert.Equal(36, Shapes.BoxFaces.Length);
			for (int t = 0; t < 36; t += 3)
			{
				Assert.True(Outwardness(p, Shapes.BoxFaces[t], Shapes.BoxFaces[t + 1], Shapes.BoxFaces[t + 2], 1, 2, 3) > 0, $"box triangle {t / 3}");
			}
		}

		[Fact]
		public void BoxCoversEveryFaceOnce()
		{
			// Each of the 6 faces gets 2 triangles: the face normals' signs over the triangles are balanced.
			var p = new float[24];
			for (int i = 0; i < 8; i++)
			{
				Shapes.BoxCorner(i, 0, 0, 0, 1, 1, 1, out p[i * 3], out p[i * 3 + 1], out p[i * 3 + 2]);
			}
			var perFace = new System.Collections.Generic.Dictionary<string, int>();
			for (int t = 0; t < 36; t += 3)
			{
				int a = Shapes.BoxFaces[t], b = Shapes.BoxFaces[t + 1], c = Shapes.BoxFaces[t + 2];
				for (int axis = 0; axis < 3; axis++)
				{
					if (p[a * 3 + axis] == p[b * 3 + axis] && p[b * 3 + axis] == p[c * 3 + axis])
					{
						string key = axis + (p[a * 3 + axis] > 0 ? "+" : "-");
						perFace.TryGetValue(key, out int seen);
						perFace[key] = seen + 1;
					}
				}
			}
			Assert.Equal(6, perFace.Count);
			Assert.All(perFace.Values, n => Assert.Equal(2, n));
		}

		[Theory]
		[InlineData(-1, 0f)]  // sphere
		[InlineData(0, 0.7f)] // capsule along x
		[InlineData(1, 0.7f)] // along y
		[InlineData(2, 0.7f)] // along z
		public void EveryEllipsoidFaceFacesOut(int axis, float halfLength)
		{
			var p = Shapes.EllipsoidPoints(0.5f, 0.8f, 0.6f, halfLength, axis);
			var tris = Shapes.EllipsoidTriangles();
			int checkedTris = 0;
			for (int t = 0; t < tris.Length; t += 3)
			{
				double o = Outwardness(p, tris[t], tris[t + 1], tris[t + 2], 0, 0, 0);
				// Triangles collapsed onto a pole have no area; every other one must face out.
				if (System.Math.Abs(o) < 1e-9)
				{
					continue;
				}
				Assert.True(o > 0, $"ellipsoid triangle {t / 3}");
				checkedTris++;
			}
			Assert.True(checkedTris >= Shapes.EllipsoidLat * Shapes.EllipsoidLon);
		}
	}
}
