using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using SubCraft.Link;
using Xunit;

namespace SubCraft.Tests
{
	public class ProtoLayoutTests
	{
		private static readonly JsonElement Layout = JsonDocument.Parse(File.ReadAllText(FindRepoFile("protocol/layout.json"))).RootElement;

		internal static string FindRepoFile(string rel)
		{
			for (string dir = AppContext.BaseDirectory; dir != null; dir = Path.GetDirectoryName(dir))
			{
				string p = Path.Combine(dir, rel);
				if (File.Exists(p))
				{
					return p;
				}
			}
			throw new FileNotFoundException(rel);
		}

		private static long C(string name) => Layout.GetProperty("constants").GetProperty(name).GetInt64();
		private static long Size(string s) => Layout.GetProperty("structs").GetProperty(s).GetProperty("size").GetInt64();
		private static long F(string s, string f) => Layout.GetProperty("structs").GetProperty(s).GetProperty("fields").GetProperty(f).GetInt64();

		[Fact]
		public void Constants()
		{
			Assert.Equal(Proto.Version, (uint)Layout.GetProperty("version").GetInt32());
			Assert.Equal(Proto.Magic, (uint)C("kMagic"));
			Assert.Equal((long)Proto.HeartbeatTimeoutNs, C("kHeartbeatTimeoutNs"));
			Assert.Equal(Proto.OffHostState, C("kOffHostState"));
			Assert.Equal(Proto.OffMcState, C("kOffMcState"));
			Assert.Equal(Proto.OffOverlayCtl, C("kOffOverlayCtl"));
			Assert.Equal(Proto.OffOverlaySlotHdr, C("kOffOverlaySlotHdr"));
			Assert.Equal(Proto.OffInputRing, C("kOffInputRing"));
			Assert.Equal(Proto.OffCreatureTable, C("kOffCreatureTable"));
			Assert.Equal(Proto.OffEventRing, C("kOffEventRing"));
			Assert.Equal(Proto.OffCollisionRing, C("kOffCollisionRing"));
			Assert.Equal(Proto.CollisionRingBytes, C("kCollisionRingBytes"));
			Assert.Equal(Proto.OffOverlayPixels, C("kOffOverlayPixels"));
			Assert.Equal(Proto.OverlaySlotBytes, C("kOverlaySlotBytes"));
			Assert.Equal(Proto.OffRenderRing, C("kOffRenderRing"));
			Assert.Equal(Proto.RenderRingBytes, C("kRenderRingBytes"));
			Assert.Equal(Proto.MappingBytes, C("kMappingBytes"));
			Assert.Equal(Proto.OverlayDirty, C("kOverlayDirty"));
			Assert.Equal(Proto.InputRingEntries, C("kInputRingEntries"));
			Assert.Equal(Proto.IrHead, C("kInputRingHeadOff"));
			Assert.Equal(Proto.IrTail, C("kInputRingTailOff"));
			Assert.Equal(Proto.IrData, C("kInputRingDataOff"));
			Assert.Equal(Proto.EventRingEntries, C("kEventRingEntries"));
			Assert.Equal(Proto.ErData, C("kEventRingDataOff"));
			Assert.Equal(Proto.CrData, C("kColRingDataOff"));
			Assert.Equal(Proto.CrDataBytes, C("kColRingDataBytes"));
			Assert.Equal(Proto.RrData, C("kRenRingDataOff"));
			Assert.Equal(Proto.RrDataBytes, C("kRenRingDataBytes"));
			Assert.Equal(Proto.DumpMagic, (uint)C("kDumpMagic"));
		}

		[Fact]
		public void Structs()
		{
			Assert.Equal(Proto.HeaderBytes, Size("Header"));
			Assert.Equal(Proto.HHostPid, F("Header", "hostPid"));
			Assert.Equal(Proto.HMcHeartbeatNs, F("Header", "mcHeartbeatNs"));
			Assert.Equal(Proto.HostStateBytes, Size("HostState"));
			var hs = new Dictionary<string, long>
			{
				["seq"] = Proto.HsSeq, ["flags"] = Proto.HsFlags, ["worldId"] = Proto.HsWorldId, ["collisionEpoch"] = Proto.HsCollisionEpoch,
				["posX"] = Proto.HsPosX, ["posY"] = Proto.HsPosY, ["posZ"] = Proto.HsPosZ, ["yaw"] = Proto.HsYaw, ["pitch"] = Proto.HsPitch,
				["teleportSeq"] = Proto.HsTeleportSeq, ["viewportW"] = Proto.HsViewportW, ["viewportH"] = Proto.HsViewportH,
				["dayFraction"] = Proto.HsDayFraction, ["oxygen"] = Proto.HsOxygen, ["oxygenCapacity"] = Proto.HsOxygenCapacity,
			};
			foreach (var kv in hs) Assert.True(kv.Value == F("HostState", kv.Key), "HostState." + kv.Key);
			Assert.Equal(Proto.McStateBytes, Size("McState"));
			var ms = new Dictionary<string, long>
			{
				["seq"] = Proto.MsSeq, ["flags"] = Proto.MsFlags, ["x"] = Proto.MsX, ["y"] = Proto.MsY, ["z"] = Proto.MsZ, ["yaw"] = Proto.MsYaw,
				["pitch"] = Proto.MsPitch, ["eyeHeight"] = Proto.MsEyeHeight, ["sensitivity"] = Proto.MsSensitivity,
				["teleportAck"] = Proto.MsTeleportAck, ["guiScale"] = Proto.MsGuiScale, ["frameCounter"] = Proto.MsFrameCounter,
				["fovDeg"] = Proto.MsFov, ["bobPhase"] = Proto.MsBobPhase, ["bobAmount"] = Proto.MsBobAmount, ["handFovDeg"] = Proto.MsHandFov, ["hurtTiltDeg"] = Proto.MsHurtTilt, ["hurtDirDeg"] = Proto.MsHurtDir, ["deathRollDeg"] = Proto.MsDeathRoll, ["eyeX"] = Proto.MsEyeX,
				["eyeY"] = Proto.MsEyeY, ["eyeZ"] = Proto.MsEyeZ, ["tickNs"] = Proto.MsTickNs, ["prevX"] = Proto.MsPrevX, ["prevY"] = Proto.MsPrevY,
				["prevZ"] = Proto.MsPrevZ, ["curX"] = Proto.MsCurX, ["curY"] = Proto.MsCurY, ["curZ"] = Proto.MsCurZ, ["tickEyeO"] = Proto.MsTickEyeO,
				["tickEye"] = Proto.MsTickEye, ["walkDistO"] = Proto.MsWalkDistO, ["walkDist"] = Proto.MsWalkDist, ["bobO"] = Proto.MsBobO,
				["bob"] = Proto.MsBob, ["tickMs"] = Proto.MsTickMs, ["cameraMode"] = Proto.MsCameraMode, ["cameraDistance"] = Proto.MsCameraDistance,
				["health"] = Proto.MsHealth, ["maxHealth"] = Proto.MsMaxHealth, ["food"] = Proto.MsFood, ["saturation"] = Proto.MsSaturation,
				["air"] = Proto.MsAir, ["maxAir"] = Proto.MsMaxAir,
			};
			foreach (var kv in ms) Assert.True(kv.Value == F("McState", kv.Key), "McState." + kv.Key);
			Assert.Equal(Proto.SlotHdrBytes, Size("OverlaySlotHdr"));
			Assert.Equal(Proto.ShFrameId, F("OverlaySlotHdr", "frameId"));
			Assert.Equal(Proto.OffCommandBox, C("kOffCommandBox"));
			Assert.Equal(Proto.CommandTextBytes, C("kCommandTextBytes"));
			Assert.Equal(Proto.CommandReplyBytes, C("kCommandReplyBytes"));
			Assert.Equal(Proto.CbAck, F("CommandBox", "ack"));
			Assert.Equal(Proto.CbStatus, F("CommandBox", "status"));
			Assert.Equal(Proto.CbTextLen, F("CommandBox", "textLen"));
			Assert.Equal(Proto.CbText, F("CommandBox", "text"));
			Assert.Equal(Proto.CbReply, F("CommandBox", "reply"));
			Assert.Equal(Proto.InputEventBytes, Size("InputEvent"));
			Assert.Equal(Proto.EventBytes, Size("McEvent"));
			Assert.Equal(Proto.CreatureRecordBytes, Size("CreatureRecord"));
			Assert.Equal(Proto.CrecName, F("CreatureRecord", "name"));
			Assert.Equal(Proto.CtRecords, F("CreatureTable", "creatures"));
			Assert.Equal(Proto.ColRegionBytes, Size("ColRegion"));
			Assert.Equal(Proto.ColBlockBytes, Size("ColBlock"));
			Assert.Equal(Proto.ColTriBytes, Size("ColTri"));
			Assert.Equal(Proto.ColDryHeaderBytes, Size("ColDryHeader"));
			Assert.Equal(Proto.DryBoxBytes, Size("DryBox"));
			Assert.Equal(Proto.ColBiomesBytes, Size("ColBiomes"));
			Assert.Equal(Proto.RenCollidersBytes, Size("RenColliders"));
			Assert.Equal(Proto.RenBoxBytes, Size("RenBox"));
			Assert.Equal(Proto.RenSoundBytes, Size("RenSound"));
			Assert.Equal(12, F("ColBiomes", "nameCount"));
			Assert.Equal(24, F("DryBox", "id"));
			Assert.Equal(36, F("ColTri", "flags"));
			Assert.Equal(Proto.TriMaterialShift, C("kTriMaterialShift"));
			Assert.Equal(Proto.ColBlockBits, F("ColBlock", "bits"));
			Assert.Equal(Proto.RenVertexBytes, Size("RenVertex"));
			Assert.Equal(Proto.RenBatchBytes, Size("RenBatch"));
		}
	}

	public class CoordsTests
	{
		[Theory]
		[InlineData(0f, 0f, 0.0, 0.0, 1.0)]    // MC yaw 0 faces +Z (south) = Unity -Z
		[InlineData(90f, 0f, -1.0, 0.0, 0.0)]  // MC yaw 90 faces -X (west)
		[InlineData(180f, 0f, 0.0, 0.0, -1.0)] // MC north = Unity +Z
		[InlineData(0f, 90f, 0.0, -1.0, 0.0)]  // pitch 90 looks straight down
		public void McLookToUnity(float yaw, float pitch, double mx, double my, double mz)
		{
			Coords.LookToUnity(yaw, pitch, out double fx, out double fy, out double fz);
			Assert.Equal(mx, fx, 6);
			Assert.Equal(my, fy, 6);
			Assert.Equal(-mz, fz, 6);
		}

		[Fact]
		public void LookRoundTrips()
		{
			for (float yaw = -179; yaw < 180; yaw += 7.3f)
			{
				for (float pitch = -85; pitch <= 85; pitch += 17)
				{
					Coords.LookToUnity(yaw, pitch, out double fx, out double fy, out double fz);
					Coords.LookToMc(fx, fy, fz, out float y2, out float p2);
					Assert.Equal(0.0, Coords.WrapDegrees(y2 - yaw), 3);
					Assert.Equal(pitch, p2, 3);
				}
			}
		}

		[Theory]
		[InlineData(0f)]
		[InlineData(37f)]
		[InlineData(250f)]
		public void UnityEulerYawMatchesForwardVector(float unityYaw)
		{
			double r = unityYaw * Math.PI / 180.0;
			Coords.LookToMc(Math.Sin(r), 0, Math.Cos(r), out float yaw, out float pitch);
			Assert.Equal(0.0, Coords.WrapDegrees(yaw - Coords.UnityYawToMc(unityYaw)), 3);
			Assert.Equal(0f, pitch, 3);
		}

		[Fact]
		public void BobViewMatchesMinecraft()
		{
			Coords.BobView(0.3f, 0f, out float tx, out float ty, out float roll, out float pitch);
			Assert.Equal((0f, 0f, 0f, 0f), (tx, ty, roll, pitch));
			// GameRenderer.bobView at phase 0, bob 1: translate (0, -1, 0), roll 0, pitch |cos(-0.2)| * 5.
			Coords.BobView(0f, 1f, out tx, out ty, out roll, out pitch);
			Assert.Equal(0f, tx, 5);
			Assert.Equal(-1f, ty, 5);
			Assert.Equal(0f, roll, 5);
			Assert.Equal(4.90033f, pitch, 4);
		}

		[Fact]
		public void PositionsFlipZ()
		{
			Coords.ToMc(1.5, -20, 7, out double x, out double y, out double z);
			Assert.Equal((1.5, -20.0, -7.0), (x, y, z));
			Coords.ToUnity(x, y, z, out double ux, out double uy, out double uz);
			Assert.Equal((1.5, -20.0, 7.0), (ux, uy, uz));
		}
	}

	public class KeyMapTests
	{
		[Theory]
		[InlineData(119, 87)]  // W
		[InlineData(97, 65)]   // A
		[InlineData(32, 32)]   // Space
		[InlineData(304, 340)] // LeftShift
		[InlineData(306, 341)] // LeftControl
		[InlineData(27, 256)]  // Escape
		[InlineData(9, 258)]   // Tab
		[InlineData(13, 257)]  // Return
		[InlineData(49, 49)]   // Alpha1 (hotbar)
		[InlineData(282, 290)] // F1
		[InlineData(296, 304)] // F15
		[InlineData(273, 265)] // UpArrow
		[InlineData(310, 343)] // LeftCommand
		[InlineData(4242, -1)] // not a key
		public void Keys(int unity, int glfw) => Assert.Equal(glfw, KeyMap.ToGlfwKey(unity));

		[Fact]
		public void MouseButtons()
		{
			Assert.Equal(0, KeyMap.ToGlfwMouseButton(323));
			Assert.Equal(1, KeyMap.ToGlfwMouseButton(324));
			Assert.Equal(2, KeyMap.ToGlfwMouseButton(325));
			Assert.Equal(-1, KeyMap.ToGlfwMouseButton(119));
		}

		[Fact]
		public void NoTwoUnityKeysShareAGlfwKey()
		{
			var seen = new HashSet<int>();
			foreach (int u in KeyMap.MappedUnityKeys) Assert.True(seen.Add(KeyMap.ToGlfwKey(u)), "duplicate GLFW key for Unity " + u);
		}
	}

	public unsafe class LinkViewTests : IDisposable
	{
		private readonly byte* mem = (byte*)NativeMemory.AllocZeroed((nuint)Proto.MappingBytes);
		private readonly LinkView view;

		public LinkViewTests()
		{
			view = new LinkView(mem);
			view.InitAsHost(1234);
		}

		public void Dispose() => NativeMemory.Free(mem);

		[Fact]
		public void HeaderWrittenMagicLast()
		{
			Assert.Equal(Proto.Magic, view.U32(Proto.HMagic));
			Assert.Equal(Proto.Version, view.U32(Proto.HVersion));
			Assert.Equal(1234, view.I32(Proto.HHostPid));
		}

		[Fact]
		public void InputRingEntriesLandWhereMinecraftReadsThem()
		{
			for (int i = 0; i < Proto.InputRingEntries + 5; i++) view.PushInput(Proto.InKey, 87, i, 0, 0);
			long head = view.I64(Proto.OffInputRing + Proto.IrHead);
			Assert.Equal(Proto.InputRingEntries + 5, head);
			long e = Proto.OffInputRing + Proto.IrData + ((head - 1) & (Proto.InputRingEntries - 1)) * Proto.InputEventBytes;
			Assert.Equal(Proto.InKey, *(ushort*)(mem + e));
			Assert.Equal(87, *(ushort*)(mem + e + 2));
			Assert.Equal(Proto.InputRingEntries + 4, view.I32(e + 4));
		}

		[Fact]
		public void CollisionRingPadsAndRefusesWhenFull()
		{
			var big = new byte[(int)(Proto.CrDataBytes / 3) - 13];
			// Minecraft's consumer: read messages, follow pads, free space.
			long tail = 0;
			int Consume()
			{
				int n = 0;
				long head = view.I64Acquire(Proto.OffCollisionRing + Proto.CrHead);
				while (tail < head)
				{
					long pos = tail % Proto.CrDataBytes;
					long at = Proto.OffCollisionRing + Proto.CrData + pos;
					if (view.U32(at) == Proto.ColPad) { tail += Proto.CrDataBytes - pos; continue; }
					Assert.Equal(Proto.ColRegion, view.U32(at));
					Assert.Equal(big.Length, view.I32(at + 4));
					Assert.Equal(big[0], mem[at + 8]);
					tail += LinkView.Align8(8 + big.Length);
					n++;
				}
				view.I64Release(Proto.OffCollisionRing + Proto.CrTail, tail);
				return n;
			}
			Assert.True(view.TryWriteCollision(Proto.ColRegion, big, big.Length));
			Assert.True(view.TryWriteCollision(Proto.ColRegion, big, big.Length));
			Assert.True(view.TryWriteCollision(Proto.ColRegion, big, big.Length));
			Assert.False(view.TryWriteCollision(Proto.ColRegion, big, big.Length));
			Assert.Equal(3, Consume());
			for (byte round = 1; round < 8; round++)
			{
				big[0] = round;
				Assert.True(view.TryWriteCollision(Proto.ColRegion, big, big.Length));
				Assert.Equal(1, Consume());
			}
		}

		[Fact]
		public void McStateSeqlockNeverTears()
		{
			bool stop = false;
			var writer = new Thread(() =>
			{
				int seq = 0;
				for (int v = 1; !Volatile.Read(ref stop); v++)
				{
					view.I32Release(Proto.OffMcState + Proto.MsSeq, ++seq);
					Thread.MemoryBarrier();
					view.Put(Proto.OffMcState + Proto.MsX, (double)v);
					view.Put(Proto.OffMcState + Proto.MsY, (double)v);
					view.Put(Proto.OffMcState + Proto.MsZ, (double)v);
					view.Put(Proto.OffMcState + Proto.MsAir, (uint)v);
					view.I32Release(Proto.OffMcState + Proto.MsSeq, ++seq);
					Thread.SpinWait(200); // a real writer publishes once per frame, not continuously
				}
			});
			writer.Start();
			int good = 0;
			var until = DateTime.UtcNow.AddMilliseconds(300);
			while (DateTime.UtcNow < until)
			{
				if (view.ReadMcState(out var s))
				{
					Assert.Equal(s.X, s.Y);
					Assert.Equal(s.Y, s.Z);
					Assert.Equal((uint)s.Z, s.Air);
					good++;
				}
			}
			Volatile.Write(ref stop, true);
			writer.Join();
			Assert.True(good > 200, "consistent reads: " + good); // the point is the asserts above: no torn read
		}

		[Fact]
		public void OverlayTakesWhatMinecraftPublished()
		{
			// Minecraft's writer: back starts at 1; publish = xchg(state, back | dirty), back = old & 3.
			int back = 1;
			void Publish(long id)
			{
				long hdr = Proto.OffOverlaySlotHdr + back * Proto.SlotHdrBytes;
				view.Put(hdr + Proto.ShWidth, 4);
				view.Put(hdr + Proto.ShHeight, 2);
				view.Put(hdr + Proto.ShFrameId, id);
				int old = Interlocked.Exchange(ref *(int*)(mem + Proto.OffOverlayCtl), back | Proto.OverlayDirty);
				back = old & 3;
			}
			Assert.False(view.TryTakeOverlay(out _));
			for (long id = 1; id <= 9; id++)
			{
				Publish(id);
				if (id % 3 == 0) Publish(++id);
				Assert.True(view.TryTakeOverlay(out var f));
				Assert.Equal(id, f.FrameId);
				Assert.NotEqual(back, (int)((f.PixelsOff - Proto.OffOverlayPixels) / Proto.OverlaySlotBytes));
				Assert.False(view.TryTakeOverlay(out _));
			}
		}

		[Fact]
		public void HostLinkCreatesASparseFileOfTheRightSize()
		{
			string path = Path.Combine(Path.GetTempPath(), "subcraft-test-" + Guid.NewGuid().ToString("N"), "link.bin");
			using (var link = new HostLink(path))
			{
				Assert.Equal(Proto.MappingBytes, new FileInfo(path).Length);
				Assert.Equal(Proto.Magic, link.View.U32(Proto.HMagic));
				Assert.False(link.McAlive(Platform.MonoNanos()));
			}
			Directory.Delete(Path.GetDirectoryName(path), true);
		}

		[Fact]
		public void MonoClockMatchesPythonsUptimeRaw()
		{
			// The JVM side was measured equal to CLOCK_UPTIME_RAW; check ours is the same clock.
			if (!Platform.Mac) return;
			long a = Platform.MonoNanos();
			var psi = new System.Diagnostics.ProcessStartInfo("python3", "-c \"import time;print(time.clock_gettime_ns(time.CLOCK_UPTIME_RAW))\"") { RedirectStandardOutput = true };
			using var p = System.Diagnostics.Process.Start(psi);
			long py = long.Parse(p.StandardOutput.ReadToEnd().Trim());
			long b = Platform.MonoNanos();
			Assert.InRange(py, a, b);
		}
	}
}
