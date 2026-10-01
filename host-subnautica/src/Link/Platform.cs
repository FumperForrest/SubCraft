using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace SubCraft.Link
{
	/// <summary>
	/// Everything that differs between macOS and Windows on the host side of the link: where the
	/// shared file lives and the cross-process clock.
	/// </summary>
	public static class Platform
	{
		public static readonly bool Mac = File.Exists("/System/Library/CoreServices/SystemVersion.plist");
		public static readonly bool Windows = Path.DirectorySeparatorChar == '\\';

		private const uint ClockUptimeRaw = 8; // <time.h> CLOCK_UPTIME_RAW

		[DllImport("/usr/lib/libSystem.dylib")]
		private static extern ulong clock_gettime_nsec_np(uint clockId);

		/// <summary>
		/// Monotonic nanoseconds, comparable across processes: CLOCK_UPTIME_RAW on macOS (what
		/// HotSpot's nanoTime reads there, also under Rosetta), QueryPerformanceCounter in ns on Windows.
		/// </summary>
		public static long MonoNanos()
		{
			if (Mac)
			{
				return (long)clock_gettime_nsec_np(ClockUptimeRaw);
			}
			long ticks = Stopwatch.GetTimestamp();
			return (long)(ticks * (1_000_000_000.0 / Stopwatch.Frequency));
		}

		/// <summary>macOS $TMPDIR/subcraft, Windows %LOCALAPPDATA%\SubCraft; SUBCRAFT_DIR overrides.</summary>
		public static string SharedDir()
		{
			string over = Environment.GetEnvironmentVariable("SUBCRAFT_DIR");
			if (!string.IsNullOrEmpty(over))
			{
				return over;
			}
			if (Windows)
			{
				return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SubCraft");
			}
			string tmp = Environment.GetEnvironmentVariable("TMPDIR");
			return Path.Combine(string.IsNullOrEmpty(tmp) ? "/tmp" : tmp, "subcraft");
		}

		/// <summary>The link file; SUBCRAFT_LINK overrides.</summary>
		public static string LinkFile()
		{
			string over = Environment.GetEnvironmentVariable("SUBCRAFT_LINK");
			return !string.IsNullOrEmpty(over) ? over : Path.Combine(SharedDir(), Proto.LinkFileName);
		}

		public static int Pid() => Process.GetCurrentProcess().Id;

		public static bool ProcessAlive(int pid)
		{
			if (pid == 0)
			{
				return false;
			}
			try
			{
				return !Process.GetProcessById(pid).HasExited;
			}
			catch (Exception)
			{
				return false;
			}
		}
	}
}
