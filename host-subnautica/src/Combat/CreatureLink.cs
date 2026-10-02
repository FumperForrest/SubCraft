using System.Collections.Generic;
using HarmonyLib;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Combat
{
	/// <summary>
	/// Combat between Minecraft's player and Subnautica's creatures (MISSION.md Phase 4).
	/// - Creatures near the player go to Minecraft in the creature table; Minecraft mirrors each as
	///   an invisible proxy with the creature's box, so swords, crits, arrows and sweeps hit it with
	///   Minecraft's own rules. A hit comes back as kEvHitCreature: Subnautica's LiveMixin takes the
	///   damage (x DamageToSubnautica) and the creature's Rigidbody the knockback.
	/// - Minecraft owns the player's health: whatever Subnautica would do to its player (bites,
	///   suffocation, heat) is cancelled here and sent as kInHurt (x DamageToMinecraft), after
	///   Subnautica's own reductions (suits, armour) so they still count.
	/// </summary>
	public static class CreatureLink
	{
		private const float Radius = 40f;
		private const float Period = 0.1f;

		private static readonly Dictionary<uint, GameObject> byId = new Dictionary<uint, GameObject>();
		private static readonly List<LinkView.CreatureRecord> records = new List<LinkView.CreatureRecord>();
		private static readonly HashSet<GameObject> seen = new HashSet<GameObject>();
		private static readonly List<Collider> scratch = new List<Collider>();
		private static readonly Collider[] overlap = new Collider[2048];
		private static float next;
		public static int Count => records.Count;
		public static int Hits, Hurts;

		public static void Frame(LinkView view, bool active)
		{
			if (Time.unscaledTime < next)
			{
				return;
			}
			next = Time.unscaledTime + Period;
			records.Clear();
			byId.Clear();
			seen.Clear();
			var player = global::Player.main;
			if (active && player != null)
			{
				int n = Physics.OverlapSphereNonAlloc(player.transform.position, Radius, overlap, ~0, QueryTriggerInteraction.Ignore);
				for (int i = 0; i < n && records.Count < Proto.MaxCreatures; i++)
				{
					// Creatures, and objects a Minecraft tool may move: vehicles and loose physics items.
					var c = overlap[i];
					var creature = c.GetComponentInParent<Creature>();
					GameObject root = creature != null ? creature.gameObject : null;
					bool isObject = false;
					if (root == null)
					{
						var vehicle = c.GetComponentInParent<Vehicle>();
						var item = vehicle == null ? c.GetComponentInParent<Pickupable>() : null;
						root = vehicle != null ? vehicle.gameObject : item != null && !item.attached && item.GetComponent<Rigidbody>() != null ? item.gameObject : null;
						isObject = root != null;
					}
					if (root == null || root == player.gameObject || !seen.Add(root))
					{
						continue;
					}
					var live = root.GetComponent<LiveMixin>();
					if ((live == null ? !isObject : !live.IsAlive()) || !Box(root, out Bounds b))
					{
						continue;
					}
					uint id = (uint)root.GetInstanceID();
					byId[id] = root;
					uint flags = isObject ? Proto.CreatureObject : 0;
					if (root.GetComponent<AggressiveWhenSeeTarget>() != null)
					{
						flags |= Proto.CreatureHostile;
					}
					if (live != null && live.invincible)
					{
						flags |= Proto.CreatureInvulnerable;
					}
					records.Add(new LinkView.CreatureRecord
					{
						Id = id,
						Flags = flags,
						// Bottom centre of the box, MC coords (z mirrored).
						X = b.center.x,
						Y = b.min.y,
						Z = -b.center.z,
						Yaw = SubCraft.Link.Coords.UnityYawToMc(root.transform.eulerAngles.y),
						// Minecraft boxes are square: the larger horizontal extent.
						Width = Mathf.Max(b.size.x, b.size.z),
						Height = b.size.y,
						HealthFrac = live != null ? live.GetHealthFraction() : 1f,
						Name = CraftData.GetTechType(root).AsString(),
					});
				}
			}
			view.WriteCreatures(records);
		}

		/// <summary>The object behind a creature-table id (still near the player), or null.</summary>
		public static GameObject Find(uint id) => byId.TryGetValue(id, out var go) ? go : null;

		/// <summary>Harness: the creatures in the table and their health.</summary>
		public static string Describe()
		{
			var sb = new System.Text.StringBuilder($"{records.Count} creatures, hits {Hits}, hurts {Hurts}, mob stand-ins {MobStandIns.Count}, mob bites {MobStandIns.Bites}, grab events {GrabController.Events}, held {GrabController.Held}\n");
			foreach (var r in records)
			{
				string hostile = (r.Flags & Proto.CreatureHostile) != 0 ? " hostile" : "";
				sb.Append($"{r.Id} {r.Name} at ({r.X:F1}, {r.Y:F1}, {r.Z:F1}) box {r.Width:F1}x{r.Height:F1} health {r.HealthFrac:F2}{hostile}\n");
			}
			return sb.ToString();
		}

		/// <summary>The creature's solid colliders' world bounds.</summary>
		private static bool Box(GameObject root, out Bounds bounds)
		{
			root.GetComponentsInChildren(false, scratch);
			bool any = false;
			bounds = default;
			foreach (var c in scratch)
			{
				if (c.isTrigger || !c.enabled)
				{
					continue;
				}
				if (!any)
				{
					bounds = c.bounds;
					any = true;
				}
				else
				{
					bounds.Encapsulate(c.bounds);
				}
			}
			return any;
		}

		/// <summary>kEvHitCreature: id, a = MC damage, b/c = knockback direction x/z (MC), d = strength.</summary>
		public static void Hit(in LinkView.McEvent ev)
		{
			if (!byId.TryGetValue(ev.Id, out var creature) || creature == null)
			{
				return;
			}
			var player = global::Player.main;
			float damage = ev.A * Plugin.DamageToSubnautica.Value;
			var live = creature.GetComponent<LiveMixin>();
			if (live != null)
			{
				live.TakeDamage(damage, creature.transform.position, DamageType.Normal, player != null ? player.gameObject : null);
			}
			var rb = creature.GetComponent<Rigidbody>();
			if (rb != null && !rb.isKinematic && ev.D > 0f)
			{
				// Minecraft's knockback is a velocity change of about strength blocks/tick * 20... scaled
				// down to Subnautica's water: a shove, not a launch.
				var dir = new Vector3(ev.B, 0.1f, -ev.C).normalized;
				rb.AddForce(dir * ev.D * 8f, ForceMode.VelocityChange);
			}
			Hits++;
		}

		/// <summary>Sends Subnautica's damage to the player to Minecraft instead.</summary>
		internal static bool PlayerHurt(LiveMixin live, float originalDamage, DamageType type, GameObject dealer)
		{
			var driver = LinkDriver.Instance;
			if (driver == null || !driver.McLinked || !driver.HaveMc || !driver.Mc.Has(Proto.McInWorld) || driver.Link == null)
			{
				return false;
			}
			float damage = DamageSystem.CalculateDamage(originalDamage, type, live.gameObject, dealer);
			if (damage <= 0f)
			{
				return true;
			}
			float mc = damage * Plugin.DamageToMinecraft.Value;
			ushort kind = type == DamageType.Normal || type == DamageType.Puncture ? Proto.HurtMelee
				: type == DamageType.Collide ? Proto.HurtOther : Proto.HurtOther;
			uint attacker = 0;
			var creature = dealer != null ? dealer.GetComponentInParent<Creature>() : null;
			if (creature != null)
			{
				attacker = (uint)creature.GetInstanceID();
			}
			bool grabbed = global::Player.main != null && global::Player.main.cinematicModeActive;
			driver.Link.View.PushInput(Proto.InHurt, kind, Mathf.RoundToInt(mc * 100f), unchecked((int)attacker), grabbed ? (int)Proto.HurtGrab : 0);
			Hurts++;
			return true;
		}
	}

	/// <summary>
	/// One death (MISSION.md 3.4): Subnautica kills its player without TakeDamage (suffocation, the
	/// Cyclops exploding, the console). The Minecraft player dies with it; Subnautica's own death
	/// and respawn run as usual and the teleport handshake brings Minecraft to the respawn point.
	/// </summary>
	[HarmonyPatch(typeof(LiveMixin), nameof(LiveMixin.Kill))]
	internal static class PlayerKillPatch
	{
		private static void Prefix(LiveMixin __instance)
		{
			var player = global::Player.main;
			var driver = LinkDriver.Instance;
			if (player == null || __instance.gameObject != player.gameObject || driver == null || !driver.McLinked || driver.Link == null)
			{
				return;
			}
			// More than any armour lets through: Minecraft's death, Minecraft's death screen skipped.
			driver.Link.View.PushInput(Proto.InHurt, Proto.HurtOther, 1000 * 100, 0, 0);
			Plugin.Log.LogInfo("SubCraft: Subnautica killed its player; Minecraft's dies with it");
		}
	}

	/// <summary>Subnautica heals its player (first aid kit, ...): Minecraft's player gets it.</summary>
	[HarmonyPatch(typeof(LiveMixin), nameof(LiveMixin.AddHealth))]
	internal static class PlayerHealPatch
	{
		private static bool Prefix(LiveMixin __instance, float healthBack, ref float __result)
		{
			var player = global::Player.main;
			var driver = LinkDriver.Instance;
			if (player == null || __instance.gameObject != player.gameObject || !Hud.SurvivalDials.Active || driver?.Link == null)
			{
				return true;
			}
			driver.Link.View.PushInput(Proto.InHurt, Proto.HurtOther, -Mathf.RoundToInt(healthBack * Plugin.DamageToMinecraft.Value * 100f), 0, 0);
			__result = healthBack;
			return false;
		}
	}

	[HarmonyPatch(typeof(LiveMixin), nameof(LiveMixin.TakeDamage))]
	internal static class PlayerDamagePatch
	{
		private static bool Prefix(LiveMixin __instance, float originalDamage, DamageType type, GameObject dealer, ref bool __result)
		{
			var standIn = __instance.GetComponent<McMobStandIn>();
			if (standIn != null)
			{
				MobStandIns.Hurt(standIn, __instance, originalDamage, type, dealer);
				__result = false;
				return false;
			}
			if (global::Player.main == null || __instance.gameObject != global::Player.main.gameObject)
			{
				return true;
			}
			if (!CreatureLink.PlayerHurt(__instance, originalDamage, type, dealer))
			{
				return true;
			}
			__result = false; // Minecraft decides whether the player dies
			return false;
		}
	}
}
