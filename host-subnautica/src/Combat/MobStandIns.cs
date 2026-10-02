using System.Collections.Generic;
using HarmonyLib;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Combat
{
	/// <summary>Marks an invisible stand-in for a Minecraft mob.</summary>
	public sealed class McMobStandIn : MonoBehaviour
	{
		public uint Id;
	}

	/// <summary>
	/// Minecraft's mobs in Subnautica's ecosystem (MISSION.md 3.4): every mob in Minecraft's mob
	/// table gets an invisible Unity stand-in with its box, a LiveMixin and an EcoTarget of the
	/// type predators hunt (Shark, like the player), so a reaper or a stalker can see, chase and bite
	/// a zombie. A bite on a stand-in goes to Minecraft as kInHurtMob; the stand-in never loses
	/// health itself. Stand-ins are not terrain (CollisionHarvester skips them).
	/// </summary>
	public static class MobStandIns
	{
		private static readonly Dictionary<uint, GameObject> standIns = new Dictionary<uint, GameObject>();
		private static readonly List<LinkView.MobRecord> mobs = new List<LinkView.MobRecord>();
		private static readonly HashSet<uint> live = new HashSet<uint>();
		private static LiveMixinData data;
		private static GameObject root;
		public static int Count => standIns.Count;
		public static int Bites;

		public static void Frame(LinkView view, bool active)
		{
			if (!active || !view.ReadMobs(mobs))
			{
				if (!active)
				{
					Clear();
				}
				return;
			}
			live.Clear();
			foreach (var m in mobs)
			{
				live.Add(m.Id);
				if (!standIns.TryGetValue(m.Id, out var go) || go == null)
				{
					go = Create(m.Id);
					standIns[m.Id] = go;
				}
				var box = go.GetComponent<BoxCollider>();
				box.size = new Vector3(Mathf.Max(0.2f, m.Width), Mathf.Max(0.2f, m.Height), Mathf.Max(0.2f, m.Width));
				box.center = new Vector3(0f, box.size.y / 2f, 0f);
				go.transform.position = new Vector3(m.X, m.Y, -m.Z);
			}
			if (standIns.Count > live.Count)
			{
				var gone = new List<uint>();
				foreach (var kv in standIns)
				{
					if (!live.Contains(kv.Key))
					{
						gone.Add(kv.Key);
					}
				}
				foreach (var id in gone)
				{
					Object.Destroy(standIns[id]);
					standIns.Remove(id);
				}
			}
		}

		private static GameObject Create(uint id)
		{
			if (root == null)
			{
				root = new GameObject("SubCraft Minecraft mobs");
				Object.DontDestroyOnLoad(root);
			}
			if (data == null)
			{
				data = ScriptableObject.CreateInstance<LiveMixinData>();
				data.maxHealth = 100f;
				data.broadcastKillOnDeath = false;
			}
			var go = new GameObject($"Minecraft mob {id}");
			go.SetActive(false); // components configured before they wake
			go.transform.SetParent(root.transform, false);
			go.AddComponent<McMobStandIn>().Id = id;
			go.AddComponent<BoxCollider>();
			var rb = go.AddComponent<Rigidbody>();
			rb.isKinematic = true;
			rb.useGravity = false;
			var lm = go.AddComponent<LiveMixin>();
			lm.data = data;
			lm.health = data.maxHealth;
			go.AddComponent<EcoTarget>().type = EcoTargetType.Shark;
			go.SetActive(true);
			return go;
		}

		public static void Clear()
		{
			foreach (var go in standIns.Values)
			{
				if (go != null)
				{
					Object.Destroy(go);
				}
			}
			standIns.Clear();
		}

		/// <summary>A creature (or anything) hurt a stand-in: Minecraft's mob takes it.</summary>
		internal static bool Hurt(McMobStandIn standIn, LiveMixin live, float originalDamage, DamageType type, GameObject dealer)
		{
			var driver = LinkDriver.Instance;
			if (driver == null || driver.Link == null || !driver.McLinked)
			{
				return true;
			}
			float damage = DamageSystem.CalculateDamage(originalDamage, type, live.gameObject, dealer) * Plugin.DamageToMinecraft.Value;
			if (damage > 0f)
			{
				var creature = dealer != null ? dealer.GetComponentInParent<Creature>() : null;
				uint attacker = creature != null ? (uint)creature.GetInstanceID() : 0u;
				driver.Link.View.PushInput(Proto.InHurtMob, Proto.HurtMelee, Mathf.RoundToInt(damage * 100f), unchecked((int)standIn.Id), unchecked((int)attacker));
				Bites++;
			}
			live.health = live.maxHealth; // Minecraft owns the mob's health
			return true;
		}
	}

	/// <summary>Creatures bite players, creatures, vehicles and decoys; a Minecraft mob's stand-in counts too.</summary>
	[HarmonyPatch(typeof(MeleeAttack), "CanDealDamageTo")]
	internal static class StandInBitePatch
	{
		private static bool Prefix(GameObject target, ref bool __result)
		{
			if (target != null && target.GetComponent<McMobStandIn>() != null)
			{
				__result = true;
				return false;
			}
			return true;
		}
	}
}
