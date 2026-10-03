using System;

namespace SubCraft.Core.Collision
{
	/// <summary>
	/// Primitive colliders as triangles, in the collider's local space (the harvester transforms
	/// them to world space and mirrors them into Minecraft's). Winding: every triangle (a, b, c) has
	/// (b - a) x (c - a) pointing out of the solid, computed on the raw coordinates, which is what
	/// a Unity front face is; after the harvester's z mirror and b/c swap that becomes the protocol's
	/// "counter-clockwise seen from outside" (ColTri), which the guest's voxelizer fills by.
	///
	/// Unity-free (src/Core): compiled into the no-game tests.
	/// </summary>
	public static class Shapes
	{
		/// <summary>Box corner <paramref name="i"/> (0-7): x = +h when bit 0 xor bit 1, y = +h when bit 2, z = +h when bit 1.</summary>
		public static void BoxCorner(int i, float cx, float cy, float cz, float hx, float hy, float hz, out float x, out float y, out float z)
		{
			x = cx + (((i & 1) != 0) ^ ((i & 2) != 0) ? hx : -hx);
			y = cy + ((i & 4) != 0 ? hy : -hy);
			z = cz + ((i & 2) != 0 ? hz : -hz);
		}

		/// <summary>
		/// Twelve triangles over <see cref="BoxCorner"/>'s corners, outward. (Before 2026-10-03 every
		/// face was wound inward, so box colliders voxelized as open space.)
		/// </summary>
		public static readonly int[] BoxFaces =
		{
			0, 1, 2, 0, 2, 3, // bottom (-y)
			4, 6, 5, 4, 7, 6, // top (+y)
			0, 5, 1, 0, 4, 5, // -z
			3, 6, 7, 3, 2, 6, // +z
			0, 7, 4, 0, 3, 7, // -x
			1, 6, 2, 1, 5, 6, // +x
		};

		public const int EllipsoidLon = 10, EllipsoidLat = 8;

		/// <summary>
		/// A sphere (axis -1) or capsule (cylinder half-length along axis 0/1/2) as a lat-long grid of
		/// (Lat + 1) x Lon points, xyz interleaved, relative to its centre.
		/// </summary>
		public static float[] EllipsoidPoints(float rx, float ry, float rz, float halfLength, int axis)
		{
			var pts = new float[(EllipsoidLat + 1) * EllipsoidLon * 3];
			for (int la = 0; la <= EllipsoidLat; la++)
			{
				double theta = Math.PI * la / EllipsoidLat; // 0 = top
				float y = (float)Math.Cos(theta), r = (float)Math.Sin(theta);
				float shift = halfLength * (la <= EllipsoidLat / 2 ? 1 : -1);
				for (int lo = 0; lo < EllipsoidLon; lo++)
				{
					double phi = 2 * Math.PI * lo / EllipsoidLon;
					float vx = r * (float)Math.Cos(phi) * rx, vy = y * ry + shift, vz = r * (float)Math.Sin(phi) * rz;
					// Turn the y-axis capsule onto x or z with a rotation: swapping two coordinates
					// (as before 2026-10-03) is a mirror, which turned those capsules inside out.
					if (axis == 0)
					{
						(vx, vy) = (vy, -vx);
					}
					else if (axis == 2)
					{
						(vy, vz) = (-vz, vy);
					}
					int o = (la * EllipsoidLon + lo) * 3;
					pts[o] = vx;
					pts[o + 1] = vy;
					pts[o + 2] = vz;
				}
			}
			return pts;
		}

		/// <summary>The grid's triangles as point indices, outward (two per quad: a b d, a d c).</summary>
		public static int[] EllipsoidTriangles()
		{
			var t = new int[EllipsoidLat * EllipsoidLon * 6];
			int n = 0;
			for (int la = 0; la < EllipsoidLat; la++)
			{
				for (int lo = 0; lo < EllipsoidLon; lo++)
				{
					int a = la * EllipsoidLon + lo, b = la * EllipsoidLon + (lo + 1) % EllipsoidLon;
					int c = (la + 1) * EllipsoidLon + lo, d = (la + 1) * EllipsoidLon + (lo + 1) % EllipsoidLon;
					t[n++] = a; t[n++] = b; t[n++] = d;
					t[n++] = a; t[n++] = d; t[n++] = c;
				}
			}
			return t;
		}
	}
}
