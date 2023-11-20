﻿using HarmonyLib;
using Il2CppSystem;
using Il2CppSystem.Collections.Generic;
using Il2CppInterop.Runtime.Injection;
using System.IO;
using System.Reflection;
using System.Linq;
using System.Threading;
using FeralTweaks.Mods;
using FeralTweaks;
using Newtonsoft.Json;
using WW.Waiters;
using StrayTech;

namespace feraltweaks.Patches.AssemblyCSharp
{
    public class CoreChartDataManagerPatch
    {
        private class MirrorList<T, T2> : List<T> where T2 : T where T : BaseDef
        {
            private List<T2> delegateList;
            private bool loading = true;
            public MirrorList(List<T2> delegateList)
            {
                this.delegateList = delegateList;
                foreach (T obj in delegateList)
                    Add(obj);
                loading = false;
            }

            public override void Add(T item)
            {
                base.Add(item);
                if (!loading)
                    delegateList.Add(item.Cast<T2>());
            }

        }

        private class MirrorDict : Dictionary<string, BaseDef>
        {
            private Il2CppSystem.Collections.IDictionary delegateList;
            private Adder adder;

            private abstract class Adder
            {
                public class AdderImpl<T2> : Adder where T2 : BaseDef
                {
                    public Dictionary<string, T2> delegateList;

                    public override void Add(string item, BaseDef def)
                    {
                        delegateList.Add(item, def.Cast<T2>());
                    }
                }

                public abstract void Add(string item, BaseDef def);
            }

            private bool loading = true;

            public MirrorDict() : base()  
            {
            }

            public MirrorDict(System.IntPtr ptr) : base(ptr)
            {
            }

            public MirrorDict(Il2CppSystem.Collections.IDictionary delegateList) : base()
            {
                this.delegateList = delegateList;
            }

            public MirrorDict Populate<T2>() where T2 : BaseDef
            {

                Dictionary<string, T2> list = delegateList.Cast<Dictionary<string, T2>>();
                foreach (string k in list.Keys)
                    Add(k, list[k].Cast<T2>());
                loading = false;
                adder = new Adder.AdderImpl<T2>()
                {
                    delegateList = list
                };
                return this;
            }

            public override bool ContainsKey(string item)
            {
                if (!delegateList.Contains(item))
                {
                    Remove(item);
                    return false;
                }
                return base.ContainsKey(item);
            }

            public override void Add(string item, BaseDef value)
            {
                base.Add(item, value);
                if (!loading)
                    adder.Add(item, value);
            }

        }

        private delegate BaseDef DefCreator();
        private delegate void DefDataMerger(BaseDef def, DefComponent component, string patch);

        public static Dictionary<string, BaseDef> DefCache = new Dictionary<string, BaseDef>();
        public static System.Collections.Generic.Dictionary<string, Dictionary<string, BaseDef>> DefCacheIdLists = new System.Collections.Generic.Dictionary<string, Dictionary<string, BaseDef>>();
        private static bool patched;
        private static bool safeToLoad;

        private static System.Collections.Generic.Dictionary<string, DefDataMerger> mergers = new System.Collections.Generic.Dictionary<string, DefDataMerger>()
        {
            // BundlePackDefComponent merger
            ["BundlePackDefComponent"] = (def, component, patch) =>
            {
                BundlePackDefComponent comp = component.Cast<BundlePackDefComponent>();

                // Parse
                MergerJsons.BundlePackDefComponentMerger data = JsonConvert.DeserializeObject<MergerJsons.BundlePackDefComponentMerger>(patch);
                if (data.mode == null)
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Missing merger field: mode, expected either ADD, REMOVE or REPLACE");
                    return;
                }
                data.mode = data.mode.ToLower();
                if (data.mode != "add" && data.mode != "replace" && data.mode != "remove")
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Invalid merger field: mode, expected either ADD, REMOVE or REPLACE");
                    return;
                }
                if (data.items == null)
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Missing merger field: items");
                    return;
                }

                // Handle
                switch (data.mode)
                {
                    // Add
                    case "add":
                        {
                            foreach (string id in data.items.Keys)
                            {
                                BundlePackDefComponent.CraftableItemsEntry e = new BundlePackDefComponent.CraftableItemsEntry();
                                e.itemDefID = id;
                                e.count = data.items[id];
                                comp._craftableItems.Add(e);
                            }
                            break;
                        }

                    // Remove
                    case "remove":
                        {
                            foreach (string id in data.items.Keys)
                            {
                                BundlePackDefComponent.CraftableItemsEntry e = null;
                                foreach (BundlePackDefComponent.CraftableItemsEntry en in comp._craftableItems)
                                {
                                    if (en.itemDefID == id && en.count == data.items[id])
                                    {
                                        e = en;
                                        break;
                                    }
                                }
                                if (e != null)
                                    comp._craftableItems.Remove(e);
                            }
                            break;
                        }

                    // Remove
                    case "replace":
                        {
                            comp._craftableItems.Clear();
                            foreach (string id in data.items.Keys)
                            {
                                BundlePackDefComponent.CraftableItemsEntry e = new BundlePackDefComponent.CraftableItemsEntry();
                                e.itemDefID = id;
                                e.count = data.items[id];
                                comp._craftableItems.Add(e);
                            }
                            break;
                        }
                }
            },

            // ActorAttachNodeGroupDefComponent merger
            ["ActorAttachNodeGroupDefComponent"] = (def, component, patch) =>
            {
                ActorAttachNodeGroupDefComponent comp = component.Cast<ActorAttachNodeGroupDefComponent>();

                // Parse
                MergerJsons.MergerActorAttachNodeGroupChart data = JsonConvert.DeserializeObject<MergerJsons.MergerActorAttachNodeGroupChart>(patch);
                if (data.mode == null)
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Missing merger field: mode, expected either ADD, REMOVE or REPLACE");
                    return;
                }
                data.mode = data.mode.ToLower();
                if (data.mode != "add" && data.mode != "replace" && data.mode != "remove")
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Invalid merger field: mode, expected either ADD, REMOVE or REPLACE");
                    return;
                }
                if (data.defIDs == null)
                {
                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Missing merger field: defIDs");
                    return;
                }

                // Handle
                switch (data.mode)
                {
                    // Add
                    case "add":
                        {
                            foreach (string id in data.defIDs)
                            {
                                comp.attachNodes._defIDs.Add(id);
                            }
                            break;
                        }

                    // Remove
                    case "remove":
                        {
                            foreach (string id in data.defIDs)
                            {
                                comp.attachNodes._defIDs.Remove(id);
                            }
                            break;
                        }

                    // Remove
                    case "replace":
                        {
                            comp.attachNodes._defIDs.Clear();
                            foreach (string id in data.defIDs)
                            {
                                comp.attachNodes._defIDs.Add(id);
                            }
                            break;
                        }
                }
                comp.attachNodes._defs = BaseDef.GetDefs(comp.attachNodes._defIDs, false);
            }
        };

        [HarmonyPostfix]
        [HarmonyPatch(typeof(CraftableItemChartData), "CreateDef")]
        public static void CreateDef(CraftableItemChartData __instance)
        {
            safeToLoad = true;
            SetChartObjectInstances();
        }

        [HarmonyPrefix]
        [HarmonyPatch(typeof(LocalizationChartData), "Get")]
        public static bool Get(string inDefID, string inDefault, ref LocalizationChartData __instance, ref string __result)
        {
            if (DefCache.ContainsKey(inDefID))
            {
                __result = DefCache[inDefID].GetComponent<LocalizationDefComponent>().LocalizedString;
                return false;
            }
            return true;
        }

        [HarmonyPrefix]
        [HarmonyPatch(typeof(LocalizationChartData), "GetString")]
        public static bool GetString(string inDefID, ref string __result)
        {
            if (DefCache.ContainsKey(inDefID))
            {
                __result = DefCache[inDefID].GetComponent<LocalizationDefComponent>().LocalizedString;
                return false;
            }
            return true;
        }

        [HarmonyPostfix]
        [HarmonyPatch(typeof(WaitController), "Update")]
        public static void Update()
        {
            // Prevent cached charts from removing
            foreach (BaseDef def in DefCache.Values)
            {
                if (DefCacheIdLists.ContainsKey(def.defID))
                {
                    Dictionary<string, BaseDef> list = DefCacheIdLists[def.defID];
                    if (!list.ContainsKey(def.defID))
                        list.Add(def.defID, def);
                }
            }
        }

        [HarmonyPostfix]
        [HarmonyPatch(typeof(CoreChartDataManager), "SetChartObjectInstances")]
        public static void SetChartObjectInstances()
        {
            if (!safeToLoad)
                return;
            if (patched)
                return;
            patched = true;

            // Okay time to load over the original game
            ClassInjector.RegisterTypeInIl2Cpp<MirrorDict>();
            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Loading chart patches...");

            // Check
            if (!FeralTweaks.PatchConfig.ContainsKey("DisableClientChartPatches") || FeralTweaks.PatchConfig["DisableClientChartPatches"] != "True")
            {
                // Create patch directory
                Directory.CreateDirectory(FeralTweaksLoader.GetLoadedMod<FeralTweaks>().ConfigDir + "/chartpatches");

                // Read patches
                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Applying chart patches from configuration...");
                foreach (FileInfo file in new DirectoryInfo(FeralTweaksLoader.GetLoadedMod<FeralTweaks>().ConfigDir + "/chartpatches").GetFiles("*.cdpf", SearchOption.AllDirectories))
                {
                    string patch = File.ReadAllText(file.FullName).Replace("\t", "    ").Replace("\r", "");
                    ApplyPatch(patch, file.Name);
                }
            }

            // Mod patches
            foreach (FeralTweaksMod mod in FeralTweaksLoader.GetLoadedMods())
            {
                if (mod.ModBaseDirectory != null)
                {
                    // Check if a mod patch folder exists
                    if (Directory.Exists(mod.ModBaseDirectory + "/chartpatches"))
                    {
                        // Apply patches from it
                        FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Applying chart patches from mod '" + mod.ID + "'...");
                        foreach (FileInfo file in new DirectoryInfo(mod.ModBaseDirectory + "/chartpatches").GetFiles("*.cdpf", SearchOption.AllDirectories))
                        {
                            string patch = File.ReadAllText(file.FullName).Replace("\t", "    ").Replace("\r", "");
                            ApplyPatch(patch, file.Name);
                        }
                    }
                }
            }

            // Other patches
            if (FeralTweaks.Patches.Count != 0)
                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Applying chart patches from server...");
            foreach ((string patch, string fileName) in FeralTweaks.Patches)
            {
                ApplyPatch(patch, fileName);
            }
        }

        private static void ApplyPatch(string patch, string fileName)
        {
            try
            {
                // Parse patch
                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Loading patch: " + fileName);
                bool inPatchBlock = false;
                bool inNewDefBlock = false;
                bool inMergeDefBlock = false;
                string defID = "";
                string defComponent = "";
                string defData = "";
                ChartDataObject chart = null;
                List<BaseDef> defs = null;
                Dictionary<string, BaseDef> defByIds = null;
                DefCreator defCreator = () => new BaseDef();
                foreach (string line in patch.Split('\n'))
                {
                    if (!inPatchBlock && !inNewDefBlock && !inMergeDefBlock)
                    {
                        if (line == "" || line.StartsWith("//") || line.StartsWith("#"))
                            continue;

                        // Check command
                        System.Collections.Generic.List<string> args = new System.Collections.Generic.List<string>(line.Split(" "));
                        if (args.Count <= 1)
                        {
                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName);
                            break;
                        }
                        else
                        {
                            string cmd = args[0];
                            args.RemoveAt(0);

                            bool error = false;
                            switch (cmd)
                            {
                                case "setchart":
                                    {
                                        string chartName = args[0];
                                        switch (chartName)
                                        {
                                            case "WorldObjectChart":
                                                {
                                                    chart = ChartDataManager.instance.worldObjectChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.worldObjectChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.worldObjectChartData.defList;
                                                    defByIds = ChartDataManager.instance.worldObjectChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "LocalizationChart":
                                                {
                                                    chart = ChartDataManager.instance.localizationChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.localizationChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = new MirrorList<BaseDef, LocalizationDef>(ChartDataManager.instance.localizationChartData.defList);
                                                    defByIds = new MirrorDict(ChartDataManager.instance.localizationChartData._parsedDefsByID.Cast<Il2CppSystem.Collections.IDictionary>()).Populate<LocalizationDef>();
                                                    defCreator = () => new LocalizationDef();
                                                    break;
                                                }
                                            case "ActorAttachNodeChart":
                                                {
                                                    chart = ChartDataManager.instance.actorAttachNodeChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.actorAttachNodeChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.actorAttachNodeChartData.defList;
                                                    defByIds = ChartDataManager.instance.actorAttachNodeChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "CalendarChart":
                                                {
                                                    chart = ChartDataManager.instance.calendarChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.calendarChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.calendarChartData.defList;
                                                    defByIds = ChartDataManager.instance.calendarChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "DialogChart":
                                                {
                                                    chart = ChartDataManager.instance.dialogChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.dialogChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.dialogChartData.defList;
                                                    defByIds = ChartDataManager.instance.dialogChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ActorScaleGroupChart":
                                                {
                                                    chart = ChartDataManager.instance.actorScaleGroupChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.actorScaleGroupChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.actorScaleGroupChartData.defList;
                                                    defByIds = ChartDataManager.instance.actorScaleGroupChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ActorAttachNodeGroupChart":
                                                {
                                                    chart = ChartDataManager.instance.actorAttachNodeGroupChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.actorAttachNodeGroupChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.actorAttachNodeGroupChartData.defList;
                                                    defByIds = ChartDataManager.instance.actorAttachNodeGroupChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ActorNPCChart":
                                                {
                                                    chart = ChartDataManager.instance.actorNPCChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.actorNPCChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.actorNPCChartData.defList;
                                                    defByIds = ChartDataManager.instance.actorNPCChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ActorBodyPartNodeChart":
                                                {
                                                    chart = ChartDataManager.instance.actorBodyPartNodeChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.actorBodyPartNodeChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.actorBodyPartNodeChartData.defList;
                                                    defByIds = ChartDataManager.instance.actorBodyPartNodeChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "WorldSurfaceChart":
                                                {
                                                    chart = ChartDataManager.instance.worldSurfaceChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.worldSurfaceChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.worldSurfaceChartData.defList;
                                                    defByIds = ChartDataManager.instance.worldSurfaceChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "URLChart":
                                                {
                                                    chart = ChartDataManager.instance.urlChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.urlChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.urlChartData.defList;
                                                    defByIds = ChartDataManager.instance.urlChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "CraftableItemChart":
                                                {
                                                    chart = ChartDataManager.instance.craftableItemChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.craftableItemChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.craftableItemChartData.defList;
                                                    defByIds = ChartDataManager.instance.craftableItemChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "AudioChart":
                                                {
                                                    chart = ChartDataManager.instance.audioChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.audioChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.audioChartData.defList;
                                                    defByIds = ChartDataManager.instance.audioChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "HarvestPointChart":
                                                {
                                                    chart = ChartDataManager.instance.harvestPointChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.harvestPointChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.harvestPointChartData.defList;
                                                    defByIds = ChartDataManager.instance.harvestPointChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "QuestChart":
                                                {
                                                    chart = ChartDataManager.instance.questChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.questChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.questChartData.defList;
                                                    defByIds = ChartDataManager.instance.questChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ObjectiveChart":
                                                {
                                                    chart = ChartDataManager.instance.objectiveChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.objectiveChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.objectiveChartData.defList;
                                                    defByIds = ChartDataManager.instance.objectiveChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "TaskChart":
                                                {
                                                    chart = ChartDataManager.instance.taskChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.taskChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.taskChartData.defList;
                                                    defByIds = ChartDataManager.instance.taskChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "InteractableChart":
                                                {
                                                    chart = ChartDataManager.instance.interactableChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.interactableChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.interactableChartData.defList;
                                                    defByIds = ChartDataManager.instance.interactableChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "BundleIDChart":
                                                {
                                                    chart = ChartDataManager.instance.bundleIDChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.bundleIDChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.bundleIDChartData.defList;
                                                    defByIds = ChartDataManager.instance.bundleIDChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "BundlePackChart":
                                                {
                                                    chart = ChartDataManager.instance.bundlePackChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.bundlePackChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.bundlePackChartData.defList;
                                                    defByIds = ChartDataManager.instance.bundlePackChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ShopContentChart":
                                                {
                                                    chart = ChartDataManager.instance.shopContentChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.shopContentChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.shopContentChartData.defList;
                                                    defByIds = ChartDataManager.instance.shopContentChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ShopChart":
                                                {
                                                    chart = ChartDataManager.instance.shopChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.shopChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.shopChartData.defList;
                                                    defByIds = ChartDataManager.instance.shopChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "LootChart":
                                                {
                                                    chart = ChartDataManager.instance.lootChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.lootChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = new MirrorList<BaseDef, LootDef>(ChartDataManager.instance.lootChartData.defList);
                                                    defByIds = new MirrorDict(ChartDataManager.instance.lootChartData._parsedDefsByID.Cast<Il2CppSystem.Collections.IDictionary>()).Populate<LootDef>();
                                                    defCreator = () => new LootDef();
                                                    break;
                                                }
                                            case "NetworkedObjectsChart":
                                                {
                                                    chart = ChartDataManager.instance.networkedObjectsChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.networkedObjectsChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.networkedObjectsChartData.defList;
                                                    defByIds = ChartDataManager.instance.networkedObjectsChartData._parsedDefsByID;
                                                    defCreator = () => new NetworkedObjectsDef();
                                                    break;
                                                }
                                            case "ColorChart":
                                                {
                                                    chart = ChartDataManager.instance.colorChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.colorChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = new MirrorList<BaseDef, ColorDef>(ChartDataManager.instance.colorChartData.defList);
                                                    defByIds = new MirrorDict(ChartDataManager.instance.colorChartData._parsedDefsByID.Cast<Il2CppSystem.Collections.IDictionary>()).Populate<ColorDef>();
                                                    defCreator = () => new ColorDef();
                                                    break;
                                                }
                                            case "GlobalChart":
                                                {
                                                    chart = ChartDataManager.instance.globalChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.globalChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.globalChartData.defList;
                                                    defByIds = ChartDataManager.instance.globalChartData._parsedDefsByID;
                                                    break;
                                                }
                                            case "ImageChart":
                                                {
                                                    chart = ChartDataManager.instance.imageChartData;

                                                    // Let it load
                                                    while (chart == null)
                                                    {
                                                        chart = ChartDataManager.instance.imageChartData;
                                                        Thread.Sleep(100);
                                                    }
                                                    defs = ChartDataManager.instance.imageChartData.defList;
                                                    defByIds = ChartDataManager.instance.imageChartData._parsedDefsByID;
                                                    defCreator = () => new ImageDef();
                                                    break;
                                                }
                                            default:
                                                {
                                                    FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName + ": chart not recognized: " + chartName);
                                                    error = true;
                                                    break;
                                                }
                                        }
                                        break;
                                    }
                                case "cleardef":
                                    {
                                        if (chart == null)
                                        {
                                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName + ": no active chart set");
                                            error = true;
                                            break;
                                        }
                                        FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Clear def: " + args[0]);
                                        BaseDef def = chart.GetDef(args[0]);
                                        if (def == null)
                                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Definition not found!");
                                        else
                                        {
                                            def._components._components.Clear();
                                            DefCache[args[0]] = def;
                                        }
                                        break;
                                    }
                                case "patch":
                                    {
                                        if (chart == null)
                                        {
                                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName + ": no active chart set");
                                            error = true;
                                            break;
                                        }
                                        inPatchBlock = true;
                                        defData = "";
                                        defID = args[0];
                                        break;
                                    }
                                case "def":
                                    {
                                        if (chart == null)
                                        {
                                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName + ": no active chart set");
                                            error = true;
                                            break;
                                        }
                                        inNewDefBlock = true;
                                        defData = "";
                                        defID = args[0];
                                        break;
                                    }
                                case "merge":
                                    {
                                        if (chart == null)
                                        {
                                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName + ": no active chart set");
                                            error = true;
                                            break;
                                        }
                                        inMergeDefBlock = true;
                                        defData = "";
                                        defComponent = args[1];
                                        defID = args[0];
                                        break;
                                    }
                                default:
                                    {
                                        FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Invalid command: " + line + " found while parsing " + fileName);
                                        error = true;
                                        break;
                                    }
                            }
                            if (error)
                                break;
                        }
                    }
                    else
                    {
                        string l = line;
                        if (l == "endpatch" && inPatchBlock)
                        {
                            // Apply patch
                            inPatchBlock = false;
                            string chartPatch = defData;
                            defData = "";

                            // Get def
                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Patching " + defID + " in chart " + chart.ChartName);
                            BaseDef def = chart.GetDef(defID, true);
                            if (def == null)
                                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Definition not found!");
                            else
                            {
                                PatchDef(chartPatch, def);
                                DefCache[defID] = def;
                            }
                            continue;
                        }
                        else if (l == "enddef" && inNewDefBlock)
                        {
                            // Apply patch
                            inNewDefBlock = false;
                            string chartPatch = defData;
                            defData = "";

                            // Get def
                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Creating " + defID + " in chart " + chart.ChartName);
                            BaseDef def = chart.GetDef(defID, true);
                            if (def != null)
                                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Chart definition already exists");
                            else
                            {
                                def = defCreator();
                                def.defID = defID;
                                def.defName = defID;
                                def.LoadDataJSON(chartPatch);
                                AddCustomComponents(def, chartPatch);
                                foreach (List<ComponentBase> componentL in def._components._components.Values)
                                {
                                    foreach (ComponentBase componentI in componentL)
                                    {
                                        DefComponent comp = componentI.TryCast<DefComponent>();
                                        if (comp != null)
                                            comp.def = def;
                                    }
                                }
                                PostPatch(def, chartPatch);
                                DefCacheIdLists[def.defID] = defByIds;
                                DefCache[defID] = def;
                                defs.Add(def);
                                defByIds.Add(defID, def);
                            }
                            continue;
                        }
                        else if (l == "endmerge" && inNewDefBlock)
                        {
                            // Prepare
                            inMergeDefBlock = false;
                            string chartPatch = defData;
                            string compName = defComponent;
                            defData = "";

                            // Find component and def
                            DefComponent comp = null;
                            FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogInfo("Merging into def component " + defID + ":" + compName + " of chart " + chart.ChartName);
                            BaseDef def = chart.GetDef(defID, true);
                            if (def == null)
                            {
                                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Definition not found!");
                                return;
                            }
                            if (def._components != null && def._components._components != null)
                            {
                                foreach (List<ComponentBase> componentL in def._components._components.Values)
                                {
                                    foreach (ComponentBase componentI in componentL)
                                    {
                                        if (componentI.GetIl2CppType().FullName == compName)
                                        {
                                            // Found component
                                            comp = componentI.TryCast<DefComponent>();
                                            if (comp != null)
                                                break;
                                        }
                                    }
                                }
                            }
                            if (comp == null)
                            {
                                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Component not found!");
                                return;
                            }

                            // Find merger
                            DefDataMerger merger = mergers[compName];
                            if (merger == null)
                            {
                                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! The requested component is not supported by this merger!");
                                return;
                            }

                            // Run merger
                            merger(def, comp, chartPatch);
                            continue;
                        }
                        for (int i = 0; i < 4; i++)
                        {
                            if (!l.StartsWith(" "))
                                break;
                            l = l.Substring(1);
                        }
                        if (defData == "")
                            defData = l;
                        else
                            defData += l + "\n";
                    }
                }
            }
            catch (System.Exception e)
            {
                FeralTweaksLoader.GetLoadedMod<FeralTweaks>().LogError("Error! Exception thrown: " + e);
            }
        }

        private static void PostPatch(BaseDef defObj, string jsonData)
        {
            // Parse
            ChartObjectJson json = JsonConvert.DeserializeObject<ChartObjectJson>(jsonData);
            foreach (ChartObjectJson.ComponentDataJson comp in json.components)
            {
                foreach (List<ComponentBase> componentL in defObj._components._components.Values)
                {
                    foreach (ComponentBase componentI in componentL)
                    {
                        if (componentI.GetIl2CppType().FullName == comp.componentClass)
                        {
                            // Found component
                            FeralTweaksChartDefComponent def = componentI.TryCast<FeralTweaksChartDefComponent>();
                            if (def != null)
                            {
                                // Deserialize
                                def.Deserialize(comp.componentJSON);
                            }
                            break;
                        }
                    }
                }
            }
        }

        private static void AddCustomComponents(BaseDef defObj, string jsonData)
        {
            // Parse
            ChartObjectJson json = JsonConvert.DeserializeObject<ChartObjectJson>(jsonData);
            foreach (ChartObjectJson.ComponentDataJson comp in json.components)
            {
                bool found = false;
                foreach (List<ComponentBase> componentL in defObj._components._components.Values)
                {
                    foreach (ComponentBase componentI in componentL)
                    {
                        if (componentI.GetIl2CppType().FullName == comp.componentClass)
                        {
                            // Found component
                            found = true;
                            break;
                        }
                    }
                    if (found)
                        break;
                }
                if (!found)
                {
                    // Try to fetch mod type
                    foreach (FeralTweaksMod mod in FeralTweaksLoader.GetLoadedMods())
                    {
                        // Find assembly
                        foreach (Assembly asm in mod.Assemblies)
                        {
                            try
                            {
                                // Find type
                                System.Type t = asm.GetType(comp.componentClass);

                                // Check
                                if (t != null && t.IsAssignableTo(typeof(FeralTweaksChartDefComponent)))
                                {
                                    try
                                    {
                                        // Create instance
                                        FeralTweaksChartDefComponent inst = (FeralTweaksChartDefComponent)t.GetConstructor(new System.Type[0]).Invoke(new object[0]);

                                        // Get il2cpp type
                                        Il2CppSystem.Type tIl2Cpp = inst.GetIl2CppType();

                                        // Add component
                                        defObj._components.GetComponentListForType(tIl2Cpp).Add(inst);
                                        found = true;
                                        break;
                                    }
                                    catch
                                    { }
                                }
                            }
                            catch
                            {
                            }
                        }
                    if (found)
                        break;
                    }
                }
            }
        }

        private static void PatchDef(string chartPatch, BaseDef def)
        {
            if (def == null)
                return;

            // Patch
            Dictionary<Type, List<ComponentBase>> components = new Dictionary<Type, List<ComponentBase>>();
            if (def._components != null && def._components._components != null)
            {
                foreach (Type t in def._components._components.Keys)
                {
                    components[t] = def._components._components[t];
                }
                def._components._components.Clear();
            }
            def.LoadDataJSON(chartPatch);
            AddCustomComponents(def, chartPatch);
            PostPatch(def, chartPatch);

            if (def._components != null && def._components._components != null)
            {
                foreach (Type t in components.Keys)
                {
                    List<ComponentBase> lst = new List<ComponentBase>();
                    if (def._components._components.ContainsKey(t))
                        lst = def._components._components[t];
                    else
                        def._components._components[t] = lst;
                    foreach (ComponentBase comp in components[t])
                    {
                        lst.Add(comp);
                    }
                }
            }
        }

        public class ChartObjectJson
        {
            public string templateClass;
            public ComponentDataJson[] components = new ComponentDataJson[0];

            public class ComponentDataJson
            {
                public string componentClass;
                public System.Collections.Generic.Dictionary<string, object> componentJSON;
            }
        }

        public static class MergerJsons
        {
            public class MergerActorAttachNodeGroupChart
            {
                public string mode;
                public string[] defIDs;
            }

            public class BundlePackDefComponentMerger
            {
                public string mode;
                public Dictionary<string, int> items = new Dictionary<string, int>();
            }
        }

    }
}