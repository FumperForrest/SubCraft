using System;
using System.IO;
using System.IO.MemoryMappedFiles;

namespace SubCraft.Link
{
	/// <summary>
	/// The host end of the link: creates and sizes the shared file (sparse), maps it, resets the
	/// control blocks and beats the heartbeat. Minecraft maps the same file when it appears.
	/// </summary>
	public sealed unsafe class HostLink : IDisposable
	{
		private readonly FileStream file;
		private readonly MemoryMappedFile mmf;
		private readonly MemoryMappedViewAccessor accessor;
		private byte* ptr;

		public LinkView View { get; }
		public string Path { get; }

		public HostLink(string path)
		{
			Path = path;
			Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path));
			file = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
			if (file.Length != Proto.MappingBytes)
			{
				file.SetLength(Proto.MappingBytes); // ftruncate: sparse, only touched pages cost anything
			}
#if NETFRAMEWORK
			mmf = MemoryMappedFile.CreateFromFile(file, null, Proto.MappingBytes, MemoryMappedFileAccess.ReadWrite, null, HandleInheritability.None, true);
#else
			mmf = MemoryMappedFile.CreateFromFile(file, null, Proto.MappingBytes, MemoryMappedFileAccess.ReadWrite, HandleInheritability.None, true);
#endif
			accessor = mmf.CreateViewAccessor(0, Proto.MappingBytes, MemoryMappedFileAccess.ReadWrite);
			accessor.SafeMemoryMappedViewHandle.AcquirePointer(ref ptr);
			ptr += accessor.PointerOffset;
			View = new LinkView(ptr);
			View.InitAsHost(Platform.Pid());
			View.ResetInputWriter();
			View.ResetCollisionWriter();
			View.HostHeartbeat(Platform.MonoNanos());
		}

		/// <summary>True while Minecraft's heartbeat is fresh.</summary>
		public bool McAlive(long nowNs)
		{
			long beat = View.McHeartbeat;
			return beat != 0 && View.McPid != 0 && nowNs - beat < (long)Proto.HeartbeatTimeoutNs;
		}

		public void Dispose()
		{
			if (ptr != null)
			{
				accessor.SafeMemoryMappedViewHandle.ReleasePointer();
				ptr = null;
			}
			accessor?.Dispose();
			mmf?.Dispose();
			file?.Dispose();
		}
	}
}
