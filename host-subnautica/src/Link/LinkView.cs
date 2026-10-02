using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;

namespace SubCraft.Link
{
	/// <summary>
	/// Typed access to the shared block at a raw pointer, with the protocol's memory ordering:
	/// every seq / head / tail goes through Volatile (acquire/release) or Interlocked; payload
	/// fields are plain accesses ordered by those. Holds no connection state, so tests can put one
	/// over any buffer. The host side: writes HostState, input, collision, creatures; reads McState,
	/// events, render, overlay.
	/// </summary>
	public sealed unsafe class LinkView
	{
		private readonly byte* b;
		private int overlayFront = 2; // reader-private slot; MC writes 1 first, the middle starts at 0

		public LinkView(byte* basePtr)
		{
			b = basePtr;
		}

		public byte* Base => b;

		// ---- primitive access ----
		public int I32(long off) => *(int*)(b + off);
		public uint U32(long off) => *(uint*)(b + off);
		public long I64(long off) => *(long*)(b + off);
		public float F32(long off) => *(float*)(b + off);
		public double F64(long off) => *(double*)(b + off);
		public void Put(long off, int v) => *(int*)(b + off) = v;
		public void Put(long off, uint v) => *(uint*)(b + off) = v;
		public void Put(long off, long v) => *(long*)(b + off) = v;
		public void Put(long off, float v) => *(float*)(b + off) = v;
		public void Put(long off, double v) => *(double*)(b + off) = v;
		public void Put(long off, ushort v) => *(ushort*)(b + off) = v;
		public int I32Acquire(long off) => Volatile.Read(ref *(int*)(b + off));
		public void I32Release(long off, int v) => Volatile.Write(ref *(int*)(b + off), v);
		public long I64Acquire(long off) => Volatile.Read(ref *(long*)(b + off));
		public void I64Release(long off, long v) => Volatile.Write(ref *(long*)(b + off), v);

		public void Zero(long off, long count)
		{
			for (long i = 0; i < count; i++)
			{
				b[off + i] = 0;
			}
		}

		// ---- header ----

		/// <summary>Resets every control block (not pixel/ring payloads) and claims the mapping for this host.</summary>
		public void InitAsHost(int pid)
		{
			Zero(0, 0x1000); // header, HostState, McState, overlay control + slot headers
			Zero(Proto.OffInputRing, Proto.IrData);
			Zero(Proto.OffCreatureTable, Proto.CtRecords);
			Zero(Proto.OffMobTable, Proto.MtRecords);
			Zero(Proto.OffEventRing, Proto.ErData);
			Zero(Proto.OffCollisionRing, Proto.CrData);
			Zero(Proto.OffRenderRing, Proto.RrData);
			overlayFront = 2;
			Put(Proto.OffHeader + Proto.HVersion, Proto.Version);
			I32Release(Proto.OffHeader + Proto.HHostPid, pid);
			// Magic last: Minecraft treats the mapping as valid only once it is there.
			I32Release(Proto.OffHeader + Proto.HMagic, unchecked((int)Proto.Magic));
		}

		public void HostHeartbeat(long nowNs) => I64Release(Proto.OffHeader + Proto.HHostHeartbeatNs, nowNs);
		public long McHeartbeat => I64Acquire(Proto.OffHeader + Proto.HMcHeartbeatNs);
		public int McPid => I32Acquire(Proto.OffHeader + Proto.HMcPid);

		// ---- HostState (seqlock write) ----

		public struct HostState
		{
			public uint Flags;
			public uint WorldId;
			public uint CollisionEpoch;
			public double X, Y, Z;
			public float Yaw, Pitch;
			public uint TeleportSeq;
			public uint ViewportW, ViewportH;
			public float DayFraction;
			public float Oxygen, OxygenCapacity;
		}

		/// <summary>Single writer (the Unity main thread). Advances seq by 2 per call.</summary>
		// ---- creature table (seqlock write) ----

		public struct CreatureRecord
		{
			public uint Id, Flags;
			public float X, Y, Z, Yaw, Width, Height, HealthFrac;
			public string Name;
		}

		public void WriteCreatures(System.Collections.Generic.List<CreatureRecord> list)
		{
			long o = Proto.OffCreatureTable;
			int seq = I32(o + Proto.CtSeq);
			I32Release(o + Proto.CtSeq, seq + 1);
			Thread.MemoryBarrier();
			int count = Math.Min(list.Count, Proto.MaxCreatures);
			for (int i = 0; i < count; i++)
			{
				long r = o + Proto.CtRecords + (long)i * Proto.CreatureRecordBytes;
				var c = list[i];
				Put(r + Proto.CrecId, c.Id);
				Put(r + Proto.CrecFlags, c.Flags);
				Put(r + Proto.CrecX, c.X);
				Put(r + Proto.CrecY, c.Y);
				Put(r + Proto.CrecZ, c.Z);
				Put(r + Proto.CrecYaw, c.Yaw);
				Put(r + Proto.CrecWidth, c.Width);
				Put(r + Proto.CrecHeight, c.Height);
				Put(r + Proto.CrecHealthFrac, c.HealthFrac);
				var name = System.Text.Encoding.UTF8.GetBytes(c.Name ?? "");
				int n = Math.Min(name.Length, Proto.CreatureNameBytes - 1);
				for (int k = 0; k < Proto.CreatureNameBytes; k++)
				{
					*(b + r + Proto.CrecName + k) = k < n ? name[k] : (byte)0;
				}
			}
			Put(o + Proto.CtCount, count);
			I32Release(o + Proto.CtSeq, seq + 2);
		}

		public void WriteHostState(in HostState s)
		{
			long o = Proto.OffHostState;
			int seq = I32(o + Proto.HsSeq);
			I32Release(o + Proto.HsSeq, seq + 1);
			Thread.MemoryBarrier();
			Put(o + Proto.HsFlags, s.Flags);
			Put(o + Proto.HsWorldId, s.WorldId);
			Put(o + Proto.HsCollisionEpoch, s.CollisionEpoch);
			Put(o + Proto.HsPosX, s.X);
			Put(o + Proto.HsPosY, s.Y);
			Put(o + Proto.HsPosZ, s.Z);
			Put(o + Proto.HsYaw, s.Yaw);
			Put(o + Proto.HsPitch, s.Pitch);
			Put(o + Proto.HsTeleportSeq, s.TeleportSeq);
			Put(o + Proto.HsViewportW, s.ViewportW);
			Put(o + Proto.HsViewportH, s.ViewportH);
			Put(o + Proto.HsDayFraction, s.DayFraction);
			Put(o + Proto.HsOxygen, s.Oxygen);
			Put(o + Proto.HsOxygenCapacity, s.OxygenCapacity);
			I32Release(o + Proto.HsSeq, seq + 2);
		}

		// ---- McState (seqlock read) ----

		public struct McState
		{
			public uint Flags;
			public double X, Y, Z;
			public float Yaw, Pitch, EyeHeight, Sensitivity;
			public uint TeleportAck, GuiScale;
			public long FrameCounter;
			public float Fov, BobPhase, BobAmount, HandFov, HurtTilt, HurtDir, DeathRoll;
			public double EyeX, EyeY, EyeZ;
			public long TickNs;
			public double PrevX, PrevY, PrevZ, CurX, CurY, CurZ;
			public float TickEyeO, TickEye, WalkDistO, WalkDist, BobO, Bob, TickMs;
			public uint CameraMode;
			public float CameraDistance, Health, MaxHealth;
			public uint Food;
			public float Saturation;
			public uint Air, MaxAir;

			public bool Has(uint flag) => (Flags & flag) != 0;
		}

		/// <summary>Seqlock read; false on a torn read or before Minecraft has written once.</summary>
		public bool ReadMcState(out McState s)
		{
			long o = Proto.OffMcState;
			s = default;
			for (int attempt = 0; attempt < 100; attempt++)
			{
				int seq1 = I32Acquire(o + Proto.MsSeq);
				if (seq1 == 0)
				{
					return false;
				}
				if ((seq1 & 1) != 0)
				{
					Thread.SpinWait(20);
					continue;
				}
				s.Flags = U32(o + Proto.MsFlags);
				s.X = F64(o + Proto.MsX);
				s.Y = F64(o + Proto.MsY);
				s.Z = F64(o + Proto.MsZ);
				s.Yaw = F32(o + Proto.MsYaw);
				s.Pitch = F32(o + Proto.MsPitch);
				s.EyeHeight = F32(o + Proto.MsEyeHeight);
				s.Sensitivity = F32(o + Proto.MsSensitivity);
				s.TeleportAck = U32(o + Proto.MsTeleportAck);
				s.GuiScale = U32(o + Proto.MsGuiScale);
				s.FrameCounter = I64(o + Proto.MsFrameCounter);
				s.Fov = F32(o + Proto.MsFov);
				s.BobPhase = F32(o + Proto.MsBobPhase);
				s.BobAmount = F32(o + Proto.MsBobAmount);
				s.HandFov = F32(o + Proto.MsHandFov);
				s.HurtTilt = F32(o + Proto.MsHurtTilt);
				s.HurtDir = F32(o + Proto.MsHurtDir);
				s.DeathRoll = F32(o + Proto.MsDeathRoll);
				s.EyeX = F64(o + Proto.MsEyeX);
				s.EyeY = F64(o + Proto.MsEyeY);
				s.EyeZ = F64(o + Proto.MsEyeZ);
				s.TickNs = I64(o + Proto.MsTickNs);
				s.PrevX = F64(o + Proto.MsPrevX);
				s.PrevY = F64(o + Proto.MsPrevY);
				s.PrevZ = F64(o + Proto.MsPrevZ);
				s.CurX = F64(o + Proto.MsCurX);
				s.CurY = F64(o + Proto.MsCurY);
				s.CurZ = F64(o + Proto.MsCurZ);
				s.TickEyeO = F32(o + Proto.MsTickEyeO);
				s.TickEye = F32(o + Proto.MsTickEye);
				s.WalkDistO = F32(o + Proto.MsWalkDistO);
				s.WalkDist = F32(o + Proto.MsWalkDist);
				s.BobO = F32(o + Proto.MsBobO);
				s.Bob = F32(o + Proto.MsBob);
				s.TickMs = F32(o + Proto.MsTickMs);
				s.CameraMode = U32(o + Proto.MsCameraMode);
				s.CameraDistance = F32(o + Proto.MsCameraDistance);
				s.Health = F32(o + Proto.MsHealth);
				s.MaxHealth = F32(o + Proto.MsMaxHealth);
				s.Food = U32(o + Proto.MsFood);
				s.Saturation = F32(o + Proto.MsSaturation);
				s.Air = U32(o + Proto.MsAir);
				s.MaxAir = U32(o + Proto.MsMaxAir);
				Thread.MemoryBarrier();
				if (I32Acquire(o + Proto.MsSeq) == seq1)
				{
					return true;
				}
			}
			return false;
		}

		// ---- mob table (seqlock read, v22) ----

		public struct MobRecord
		{
			public uint Id, Flags;
			public float X, Y, Z, Width, Height, HealthFrac;
		}

		public bool ReadMobs(List<MobRecord> list)
		{
			long o = Proto.OffMobTable;
			for (int attempt = 0; attempt < 16; attempt++)
			{
				list.Clear();
				int seq1 = I32Acquire(o + Proto.MtSeq);
				if (seq1 == 0)
				{
					return false;
				}
				if ((seq1 & 1) != 0)
				{
					Thread.SpinWait(20);
					continue;
				}
				int count = Math.Min(I32(o + Proto.MtCount), Proto.MaxMobs);
				for (int i = 0; i < count; i++)
				{
					long r = o + Proto.MtRecords + (long)i * Proto.MobRecordBytes;
					list.Add(new MobRecord
					{
						Id = U32(r + Proto.MrecId),
						Flags = U32(r + Proto.MrecFlags),
						X = F32(r + Proto.MrecX),
						Y = F32(r + Proto.MrecY),
						Z = F32(r + Proto.MrecZ),
						Width = F32(r + Proto.MrecWidth),
						Height = F32(r + Proto.MrecHeight),
						HealthFrac = F32(r + Proto.MrecHealthFrac),
					});
				}
				Thread.MemoryBarrier();
				if (I32Acquire(o + Proto.MtSeq) == seq1)
				{
					return true;
				}
			}
			list.Clear();
			return false;
		}

		// ---- input ring (produce) ----

		private long inputHead;

		public void ResetInputWriter() => inputHead = I64(Proto.OffInputRing + Proto.IrHead);

		/// <summary>Queues one input event. Single producer. Minecraft drops the oldest if it falls a full ring behind.</summary>
		public void PushInput(ushort type, ushort code, int a = 0, int bArg = 0, int c = 0)
		{
			long o = Proto.OffInputRing;
			long e = o + Proto.IrData + (inputHead & (Proto.InputRingEntries - 1)) * Proto.InputEventBytes;
			Put(e, type);
			Put(e + 2, code);
			Put(e + 4, a);
			Put(e + 8, bArg);
			Put(e + 12, c);
			inputHead++;
			I64Release(o + Proto.IrHead, inputHead);
		}

		// ---- event ring (consume) ----

		public struct McEvent
		{
			public uint Type, Id;
			public float A, B, C, D;
			public uint Flags, Extra;
		}

		public int DrainEvents(List<McEvent> into)
		{
			long o = Proto.OffEventRing;
			long head = I64Acquire(o + Proto.ErHead);
			long tail = I64(o + Proto.ErTail);
			int n = 0;
			while (tail < head)
			{
				long e = o + Proto.ErData + (tail & (Proto.EventRingEntries - 1)) * Proto.EventBytes;
				into.Add(new McEvent
				{
					Type = U32(e), Id = U32(e + 4), A = F32(e + 8), B = F32(e + 12), C = F32(e + 16), D = F32(e + 20), Flags = U32(e + 24),
					Extra = U32(e + 28),
				});
				tail++;
				n++;
			}
			I64Release(o + Proto.ErTail, tail);
			return n;
		}

		// ---- collision ring (produce) ----

		private long colHead;

		public void ResetCollisionWriter() => colHead = I64(Proto.OffCollisionRing + Proto.CrHead);

		/// <summary>
		/// Writes one collision message if it fits now (header then body; messages never wrap, a pad
		/// skips to the ring start). Single producer. False when the ring is full: retry later.
		/// </summary>
		public bool TryWriteCollision(uint type, byte[] payload, int length)
		{
			long o = Proto.OffCollisionRing;
			long msg = Align8(8 + length);
			if (msg > Proto.CrDataBytes / 2)
			{
				throw new ArgumentException("collision message too large: " + msg);
			}
			long tail = I64Acquire(o + Proto.CrTail);
			long pos = colHead % Proto.CrDataBytes;
			long pad = pos + msg > Proto.CrDataBytes ? Proto.CrDataBytes - pos : 0;
			if (Proto.CrDataBytes - (colHead - tail) < msg + pad)
			{
				return false;
			}
			if (pad > 0)
			{
				Put(o + Proto.CrData + pos, Proto.ColPad);
				Put(o + Proto.CrData + pos + 4, 0u);
				colHead += pad;
				pos = 0;
			}
			long at = o + Proto.CrData + pos;
			Put(at, type);
			Put(at + 4, (uint)length);
			if (length > 0)
			{
				fixed (byte* src = payload)
				{
					Buffer.MemoryCopy(src, b + at + 8, length, length);
				}
			}
			colHead += msg;
			I64Release(o + Proto.CrHead, colHead);
			return true;
		}

		// ---- render ring (consume) ----

		public delegate void RenderSink(uint type, long payloadOff, int payloadBytes);

		public int DrainRender(RenderSink sink, int maxMessages)
		{
			long o = Proto.OffRenderRing;
			long head = I64Acquire(o + Proto.RrHead);
			long tail = I64(o + Proto.RrTail);
			int n = 0;
			while (tail < head && n < maxMessages)
			{
				long pos = tail % Proto.RrDataBytes;
				long at = o + Proto.RrData + pos;
				uint type = U32(at);
				int payload = I32(at + 4);
				if (type == Proto.RenPad)
				{
					tail += Proto.RrDataBytes - pos;
					continue;
				}
				sink(type, at + 8, payload);
				tail += Align8(8 + payload);
				n++;
			}
			I64Release(o + Proto.RrTail, tail);
			return n;
		}

		// ---- overlay (consume) ----

		public struct OverlayFrame
		{
			public int Width, Height;
			public bool BottomUp;
			public long FrameId;
			public long PixelsOff;
		}

		/// <summary>Takes the newest published overlay frame if there is one (the pixels stay ours until the next take).</summary>
		public bool TryTakeOverlay(out OverlayFrame frame)
		{
			frame = default;
			ref int state = ref *(int*)(b + Proto.OffOverlayCtl + Proto.OcState);
			if ((Volatile.Read(ref state) & Proto.OverlayDirty) == 0)
			{
				return false;
			}
			int old = Interlocked.Exchange(ref state, overlayFront);
			overlayFront = old & 3;
			long hdr = Proto.OffOverlaySlotHdr + overlayFront * Proto.SlotHdrBytes;
			frame.Width = I32(hdr + Proto.ShWidth);
			frame.Height = I32(hdr + Proto.ShHeight);
			frame.BottomUp = (I32(hdr + Proto.ShFlags) & 1) != 0;
			frame.FrameId = I64(hdr + Proto.ShFrameId);
			frame.PixelsOff = Proto.OffOverlayPixels + overlayFront * Proto.OverlaySlotBytes;
			return frame.Width > 0 && frame.Height > 0 && frame.Width <= Proto.MaxOverlayW && frame.Height <= Proto.MaxOverlayH;
		}

		public static long Align8(long n) => (n + 7) & ~7L;

		public static string CString(byte* p, int max)
		{
			int n = 0;
			while (n < max && p[n] != 0)
			{
				n++;
			}
			return Encoding.UTF8.GetString(p, n);
		}
	}
}
