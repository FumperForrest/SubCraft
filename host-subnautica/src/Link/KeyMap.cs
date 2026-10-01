using System.Collections.Generic;

namespace SubCraft.Link
{
	/// <summary>
	/// Unity KeyCode (by numeric value, so this file needs no UnityEngine) to GLFW key codes, and
	/// Unity mouse KeyCodes to GLFW mouse buttons.
	/// </summary>
	public static class KeyMap
	{
		public const int UnityMouse0 = 323; // KeyCode.Mouse0 .. Mouse6 = 323 .. 329

		private static readonly Dictionary<int, int> UnityToGlfw = Build();

		private static Dictionary<int, int> Build()
		{
			var m = new Dictionary<int, int>();
			for (int i = 0; i < 26; i++)
			{
				m[97 + i] = 65 + i; // a..z -> GLFW_KEY_A..Z
			}
			for (int i = 0; i < 10; i++)
			{
				m[48 + i] = 48 + i;   // Alpha0..9
				m[256 + i] = 320 + i; // Keypad0..9 -> GLFW_KEY_KP_0..9
			}
			for (int i = 0; i < 15; i++)
			{
				m[282 + i] = 290 + i; // F1..F15
			}
			m[32] = 32;    // Space
			m[39] = 39;    // Quote -> apostrophe
			m[44] = 44;    // Comma
			m[45] = 45;    // Minus
			m[46] = 46;    // Period
			m[47] = 47;    // Slash
			m[59] = 59;    // Semicolon
			m[61] = 61;    // Equals
			m[91] = 91;    // LeftBracket
			m[92] = 92;    // Backslash
			m[93] = 93;    // RightBracket
			m[96] = 96;    // BackQuote -> grave accent
			m[27] = 256;   // Escape
			m[13] = 257;   // Return -> Enter
			m[9] = 258;    // Tab
			m[8] = 259;    // Backspace
			m[277] = 260;  // Insert
			m[127] = 261;  // Delete
			m[275] = 262;  // RightArrow
			m[276] = 263;  // LeftArrow
			m[274] = 264;  // DownArrow
			m[273] = 265;  // UpArrow
			m[280] = 266;  // PageUp
			m[281] = 267;  // PageDown
			m[278] = 268;  // Home
			m[279] = 269;  // End
			m[301] = 280;  // CapsLock
			m[266] = 330;  // KeypadPeriod -> KP_DECIMAL
			m[267] = 331;  // KeypadDivide
			m[268] = 332;  // KeypadMultiply
			m[269] = 333;  // KeypadMinus
			m[270] = 334;  // KeypadPlus
			m[271] = 335;  // KeypadEnter
			m[272] = 336;  // KeypadEquals
			m[304] = 340;  // LeftShift
			m[306] = 341;  // LeftControl
			m[308] = 342;  // LeftAlt
			m[310] = 343;  // LeftCommand (LeftApple/LeftWindows) -> LEFT_SUPER
			m[303] = 344;  // RightShift
			m[305] = 345;  // RightControl
			m[307] = 346;  // RightAlt
			m[309] = 347;  // RightCommand -> RIGHT_SUPER
			return m;
		}

		/// <summary>Every Unity KeyCode value that has a GLFW key.</summary>
		public static IEnumerable<int> MappedUnityKeys => UnityToGlfw.Keys;

		/// <summary>GLFW key for a Unity KeyCode value, or -1.</summary>
		public static int ToGlfwKey(int unityKeyCode) => UnityToGlfw.TryGetValue(unityKeyCode, out int g) ? g : -1;

		/// <summary>GLFW mouse button (0 left, 1 right, 2 middle, 3/4 side) for KeyCode.Mouse0..4, or -1.</summary>
		public static int ToGlfwMouseButton(int unityKeyCode)
		{
			int i = unityKeyCode - UnityMouse0;
			return i >= 0 && i <= 4 ? i : -1; // Unity Mouse0 left, Mouse1 right, Mouse2 middle: same order as GLFW
		}

		/// <summary>GLFW modifier bits from held state.</summary>
		public static int GlfwMods(bool shift, bool control, bool alt, bool super)
		{
			return (shift ? 0x1 : 0) | (control ? 0x2 : 0) | (alt ? 0x4 : 0) | (super ? 0x8 : 0);
		}
	}
}
