using System;

namespace SubCraft.Link
{
	/// <summary>
	/// Unity (Subnautica) <-> Minecraft space. 1 block = 1 Unity metre, mc = (u.x, u.y, -u.z).
	/// Unity: left-handed, +Z forward, yaw (euler y) clockwise from +Z seen from above.
	/// Minecraft: yaw 0 faces +Z (south), 90 faces -X (west); pitch positive looks down.
	/// </summary>
	public static class Coords
	{
		/// <summary>
		/// Minecraft's GameRenderer.bobView, as applied to the world in view space (GL: -z forward):
		/// translate (tx, ty, 0), then roll about +Z by rollDeg, then pitch about +X by pitchDeg.
		/// phase = -(walkDist + delta * partial), amount = lerped bob (both from McState).
		/// </summary>
		public static void BobView(float phase, float amount, out float tx, out float ty, out float rollDeg, out float pitchDeg)
		{
			double a = phase * Math.PI;
			tx = (float)(Math.Sin(a) * amount * 0.5);
			ty = (float)-Math.Abs(Math.Cos(a) * amount);
			rollDeg = (float)(Math.Sin(a) * amount * 3.0);
			pitchDeg = (float)(Math.Abs(Math.Cos(a - 0.2) * amount) * 5.0);
		}

		public static void ToMc(double ux, double uy, double uz, out double mx, out double my, out double mz)
		{
			mx = ux;
			my = uy;
			mz = -uz;
		}

		public static void ToUnity(double mx, double my, double mz, out double ux, out double uy, out double uz)
		{
			ux = mx;
			uy = my;
			uz = -mz;
		}

		/// <summary>Minecraft yaw/pitch (degrees) of a Unity forward vector.</summary>
		public static void LookToMc(double fx, double fy, double fz, out float yaw, out float pitch)
		{
			// MC forward = (-sin yaw cos pitch, -sin pitch, cos yaw cos pitch) and mc = (fx, fy, -fz).
			double mcx = fx, mcy = fy, mcz = -fz;
			double len = Math.Sqrt(mcx * mcx + mcy * mcy + mcz * mcz);
			if (len < 1e-9)
			{
				yaw = 0;
				pitch = 0;
				return;
			}
			yaw = (float)WrapDegrees(Math.Atan2(-mcx, mcz) * 180.0 / Math.PI);
			pitch = (float)(-Math.Asin(Math.Max(-1.0, Math.Min(1.0, mcy / len))) * 180.0 / Math.PI);
		}

		/// <summary>Unity forward vector of a Minecraft yaw/pitch.</summary>
		public static void LookToUnity(float yaw, float pitch, out double fx, out double fy, out double fz)
		{
			double y = yaw * Math.PI / 180.0, p = pitch * Math.PI / 180.0;
			double mcx = -Math.Sin(y) * Math.Cos(p), mcy = -Math.Sin(p), mcz = Math.Cos(y) * Math.Cos(p);
			fx = mcx;
			fy = mcy;
			fz = -mcz;
		}

		/// <summary>Unity euler yaw (degrees about +Y) to Minecraft yaw: mc = unity + 180, wrapped to [-180, 180).</summary>
		public static float UnityYawToMc(float unityYaw) => (float)WrapDegrees(unityYaw + 180.0);

		public static double WrapDegrees(double d)
		{
			d %= 360.0;
			if (d >= 180.0)
			{
				d -= 360.0;
			}
			if (d < -180.0)
			{
				d += 360.0;
			}
			return d;
		}
	}
}
