import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  AppState,
  BackHandler,
  Modal,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  ToastAndroid,
  useColorScheme,
  View,
} from "react-native";
import Svg, { Path, Circle, Rect } from "react-native-svg";
import {
  SafeAreaView,
  useSafeAreaInsets,
} from "react-native-safe-area-context";
import { callAgent, closeAgent } from "../native/agent";
import { useTakuFonts } from "../theme/useTakuFonts";
import { colors } from "../theme";

type Model = {
  id: string;
  name: string;
  kind?: string;
  textInput?: boolean;
  imageInput?: boolean;
};
type Group = { id: string; name?: string; members: string[] };
type State = {
  enabled: boolean;
  assistantMode: string;
  jevEnabled: boolean;
  models: Model[];
  groups: Group[];
  bindings: Record<string, string>;
};
const features = [
  { scene: "assistant", title: "Main assistant" },
  {
    scene: "ime.vision",
    title: "Input method vision",
  },
  { scene: "screen", title: "Screen reading" },
  { scene: "memory.text", title: "Memory" },
  { scene: "jev", title: "Jev" },
  {
    scene: "org.agentos.assistant.text",
    title: "App text processing",
  },
];
const builtInNames: Record<string, string> = {
  "route-memory": "Text model",
  "route-vision": "Vision model",
  "route-assistant": "Main assistant model",
  "taku-memory-jev": "Taku Memory",
};
function groupName(group: Group): string {
  if (builtInNames[group.id]) return builtInNames[group.id];
  const name = group.name?.trim();
  if (
    !name ||
    name === group.id ||
    name.includes("/") ||
    name.startsWith("原有路由") ||
    name.startsWith("Legacy route") ||
    /^route[-/]/.test(name)
  )
    return "Custom model group";
  return name;
}
function modelName(id: string, models: Model[]): string {
  const name = models.find((m) => m.id === id)?.name;
  if (name && name !== id && !name.includes("/")) return name;
  const last = id.split("/").pop() || "";
  if (!last || /^(local|model)$/i.test(last)) return "Unnamed model";
  return last
    .replace(/[-_]+/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .replace(/\bGpt\b/g, "GPT");
}
function findGroup(state: State, scene: string) {
  const id = state.bindings[scene]?.replace(/^group\//, "");
  return state.groups.find((g) => g.id === id);
}
function featureGroup(state: State, scene: string) {
  if (!state.bindings[scene]) return "No model group selected";
  const group = findGroup(state, scene);
  return group ? groupName(group) : "Model group unavailable";
}
function compatible(scene: string | null, group: Group, models: Model[]) {
  const model = models?.find((item) => item.id === `group/${group.id}`);
  if (!scene || !model) return false;
  const decision = model.kind === "decision";
  if (scene === "jev") return decision && !!model.textInput;
  if (scene === "memory.text") return !!model.textInput;
  if (decision) return false;
  return !!(scene === "ime.vision" || scene === "screen" ? model.imageInput : model.textInput);
}
const light = {
  bg: "#F1F1F3",
  card: "#FFFFFF",
  ink: "#1B1B1B",
  sub: "#77777D",
  line: "#E9E9EC",
  soft: "#F3F3F5",
  overlay: "rgba(0,0,0,.35)",
};
const dark = {
  bg: "#141416",
  card: "#242427",
  ink: "#F5F5F5",
  sub: "#A8A8AE",
  line: "#38383D",
  soft: "#343438",
  overlay: "rgba(0,0,0,.65)",
};
type Palette = typeof light;
function Label({
  children,
  style,
  color,
  size = 16,
  medium = false,
}: {
  children: React.ReactNode;
  style?: object;
  color: string;
  size?: number;
  medium?: boolean;
}) {
  return (
    <Text
      style={[
        {
          fontFamily: medium ? "TakuChineseMedium" : "TakuChinese",
          color,
          fontSize: size,
          includeFontPadding: false,
        },
        style,
      ]}
    >
      {children}
    </Text>
  );
}
function Row({
  title,
  sub,
  onPress,
  palette,
  trailing = "›",
  icon,
  paused,
  issue,
}: {
  title: string;
  sub?: string;
  onPress: () => void;
  palette: Palette;
  trailing?: string;
  icon?: string;
  paused?: boolean;
  issue?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        { borderBottomColor: palette.line, opacity: pressed ? 0.65 : 1 },
      ]}
    >
      {icon ? (
        <View style={[styles.icon, { backgroundColor: palette.soft }]}>
          <FeatureIcon name={icon} color={palette.ink} />
        </View>
      ) : null}
      <View style={{ flex: 1, gap: 4 }}>
        <Label color={palette.ink} medium>
          {title}
        </Label>
        {sub ? (
          <View style={{ flexDirection: "row", alignItems: "center", gap: 5 }}>
            {issue ? <View style={[styles.dot, { backgroundColor: "#CC2A4A" }]} /> : null}
            <Label color={issue ? "#CC2A4A" : palette.sub} size={13}>{issue || sub}</Label>
          </View>
        ) : null}
      </View>
      {paused ? <View style={[styles.badge, { backgroundColor: palette.soft }]}><Label color={palette.sub} size={12}>Paused</Label></View> : null}
      {trailing ? <Label color={trailing === "✓" ? colors.brand : palette.sub} size={23}>{trailing}</Label> : null}
    </Pressable>
  );
}
function FeatureIcon({ name, color }: { name: string; color: string }) {
  const common = { stroke: color, strokeWidth: 1.7, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, fill: "none" };
  const paths: Record<string, React.ReactNode> = {
    assistant: <Path d="M4 5h16v11H9l-5 4V5Z" />,
    vision: <><Rect x="3" y="4" width="18" height="16" rx="2" /><Circle cx="9" cy="9" r="1.5" /><Path d="m4 18 5-5 3 3 3-4 5 6" /></>,
    screen: <><Rect x="3" y="4" width="18" height="14" rx="2" /><Path d="M8 21h8M12 18v3M7 9h10M7 13h7" /></>,
    memory: <><Path d="M7 4h10a2 2 0 0 1 2 2v14l-7-3-7 3V6a2 2 0 0 1 2-2Z" /><Path d="M8 9h8M8 12h6" /></>,
    jev: <><Circle cx="12" cy="12" r="8" /><Path d="m9 12 2 2 4-5" /></>,
    text: <Path d="M4 5h16M12 5v14M8 19h8M5 9v-4M19 9v-4" />,
    manage: <><Rect x="4" y="4" width="16" height="16" rx="3" /><Path d="M8 9h8M8 13h8M8 17h5" /></>,
  };
  return <Svg width={24} height={24} viewBox="0 0 24 24" {...common}>{paths[name]}</Svg>;
}
export default function SystemAiScreen() {
  const [fonts, fontError] = useTakuFonts();
  const insets = useSafeAreaInsets();
  const palette = useColorScheme() === "dark" ? dark : light;
  const [data, setData] = useState<State | null>(null),
    [error, setError] = useState(false),
    [scene, setScene] = useState<string | null>(null),
    [busy, setBusy] = useState(false),
    [undo, setUndo] = useState<Record<string, unknown> | null>(null);
  const mounted = useRef(true),
    inFlight = useRef(false),
    updating = useRef(false),
    foreground = useRef(
      AppState.currentState !== "background" &&
        AppState.currentState !== "inactive",
    );
  const refresh = useCallback(async () => {
    if (!foreground.current || inFlight.current || updating.current) return;
    inFlight.current = true;
    try {
      const next = await callAgent<State>("readSystemAi");
      if (mounted.current) {
        setData(next);
        setError(false);
      }
    } catch {
      if (mounted.current) setError(true);
    } finally {
      inFlight.current = false;
    }
  }, []);
  useEffect(() => {
    if (!undo) return;
    const timer = setTimeout(() => setUndo(null), 6000);
    return () => clearTimeout(timer);
  }, [undo]);
  useEffect(() => {
    mounted.current = true;
    // Polls the native system AI state (external system); state is set only after the async read resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
    const timer = setInterval(() => void refresh(), 2500);
    const sub = AppState.addEventListener("change", (s) => {
      foreground.current = s === "active";
      if (foreground.current) void refresh();
    });
    return () => {
      mounted.current = false;
      clearInterval(timer);
      sub.remove();
    };
  }, [refresh]);
  const mutate = async (
    action: Parameters<typeof callAgent>[0],
    params: Record<string, unknown>,
  ) => {
    if (updating.current) return false;
    updating.current = true;
    setBusy(true);
    setUndo(null);
    try {
      const result = await callAgent<{ undo?: Record<string, unknown> }>(
        action,
        params,
      );
      if (result.undo) setUndo(result.undo);
      else
        ToastAndroid.show(
          action === "restoreSystemAi" ? "Undone" : "Updated",
          ToastAndroid.SHORT,
        );
      return true;
    } catch (e) {
      const msg = String(e).includes("ASSISTANT_MODE_NOT_MAGPIE")
        ? "Switch the main assistant to a model service first"
        : "Update failed. Check the current settings and retry";
      ToastAndroid.show(msg, ToastAndroid.LONG);
      return false;
    } finally {
      updating.current = false;
      setBusy(false);
      void refresh();
    }
  };
  const back = useCallback(() => { void closeAgent(); }, []);
  const openManagement = async () => {
    try { await callAgent("openMagpieManagement"); }
    catch { ToastAndroid.show("Could not open Magpie management. Retry later", ToastAndroid.LONG); }
  };
  useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (scene) {
        setScene(null);
        return true;
      }
      back();
      return true;
    });
    return () => sub.remove();
  }, [back, scene]);
  if ((!fonts && !fontError) || (!data && !error))
    return (
      <SafeAreaView style={[styles.center, { backgroundColor: palette.bg }]}>
        <ActivityIndicator color={colors.brand} />
        <Label color={palette.sub} style={{ marginTop: 12 }}>
          Reading settings…
        </Label>
      </SafeAreaView>
    );
  if (!data)
    return (
      <SafeAreaView style={[styles.center, { backgroundColor: palette.bg }]}>
        <Label color={palette.ink} medium>
          System AI is temporarily unavailable
        </Label>
        <Label color={palette.sub} size={14} style={{ marginTop: 8 }}>
          Check the system service and retry
        </Label>
        <Pressable onPress={() => void refresh()} style={styles.retry}>
          <Label color={palette.ink}>Retry</Label>
        </Pressable>
      </SafeAreaView>
    );
  return (
    <SafeAreaView
      style={{ flex: 1, backgroundColor: palette.bg }}
      edges={["top", "bottom"]}
    >
      <StatusBar
        barStyle={palette === dark ? "light-content" : "dark-content"}
      />
      <View style={styles.header}>
        <Pressable
          onPress={back}
          accessibilityRole="button"
          accessibilityLabel="Back"
          style={styles.back}
        >
          <Text style={{ fontSize: 28, color: palette.ink }}>‹</Text>
        </Pressable>
        <Label color={palette.ink} size={22} medium style={{ flex: 1 }}>
          Taku AI
        </Label>
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        {error && (
          <Pressable
            onPress={() => void refresh()}
            style={{ marginBottom: 16 }}
          >
            <Label color="#CC2A4A" size={13}>
              Could not refresh. Showing the last settings read. Tap to retry.
            </Label>
          </Pressable>
        )}
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              <View style={styles.switchRow}>
                <View style={{ flex: 1, gap: 4 }}>
                  <Label color={palette.ink} medium>
                    Enable Taku AI
                  </Label>
                  <Label color={palette.sub} size={13}>
                    Turning off pauses new AI requests
                  </Label>
                </View>
                <Switch
                  disabled={busy}
                  value={data.enabled}
                  onValueChange={(enabled) =>
                    void mutate("setSystemAi", { enabled })
                  }
                  trackColor={{ true: colors.brand, false: palette.line }}
                  thumbColor="#fff"
                />
              </View>
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>
                Features
              </Label>
              <Label color={palette.sub} size={13} style={{ marginTop: 4 }}>
                Choose a model group for each feature. Changes apply immediately.
              </Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              {features.map((f, i) => (
                <Row
                  key={f.scene}
                  palette={palette}
                  title={f.title}
                  sub={
                    f.scene === "assistant" && data.assistantMode !== "magpie"
                      ? data.assistantMode === "openai"
                        ? "ChatGPT subscription"
                        : featureGroup(data, f.scene)
                      : featureGroup(data, f.scene)
                  }
                  icon={["assistant", "vision", "screen", "memory", "jev", "text"][i]}
                  paused={f.scene === "jev" && !data.jevEnabled}
                  issue={data.bindings[f.scene] && !findGroup(data, f.scene) && !(f.scene === "assistant" && data.assistantMode !== "magpie") ? "Model group unavailable" : undefined}
                  onPress={() => {
                    if (
                      f.scene === "assistant" &&
                      data.assistantMode !== "magpie"
                    ) {
                      Alert.alert(
                        "The main assistant currently uses a ChatGPT subscription",
                        "Switch to a model service in Main assistant connection before selecting a model.",
                        [
                          { text: "Cancel", style: "cancel" },
                          {
                            text: "Open connection",
                            onPress: () =>
                              void callAgent("openAssistantConnection"),
                          },
                        ],
                      );
                    } else setScene(f.scene);
                  }}
                />
              ))}
            </View>
            <View style={[styles.card, styles.manage, { backgroundColor: palette.card }]}>
              <Row
                palette={palette}
                title="Manage models and providers"
                sub="Configure models, providers, and model groups in Magpie"
                icon="manage"
                onPress={() => void openManagement()}
              />
            </View>
      </ScrollView>
      {undo && (
        <View
          style={[
            styles.undo,
            { backgroundColor: palette.ink, bottom: insets.bottom + 24 },
          ]}
        >
          <Label color={palette.bg} size={14} style={{ flex: 1 }}>
            Updated
          </Label>
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              const receipt = undo;
              setUndo(null);
              void mutate("restoreSystemAi", { undo: receipt });
            }}
          >
            <Label color={palette.bg} medium>
              Undo
            </Label>
          </Pressable>
        </View>
      )}
      <Modal
        visible={scene !== null}
        transparent
        animationType="slide"
        onRequestClose={() => setScene(null)}
      >
        <View style={[styles.modal, { backgroundColor: palette.overlay }]}>
          <Pressable style={{ flex: 1 }} onPress={() => setScene(null)} />
          <View
            style={[
              styles.sheet,
              {
                backgroundColor: palette.card,
                paddingBottom: Math.max(insets.bottom, 16) + 20,
              },
            ]}
          >
            <View style={[styles.grabber, { backgroundColor: palette.line }]} />
            <Label color={palette.ink} size={20} medium>
              Select model group
            </Label>
            <Label
              color={palette.sub}
              size={14}
              style={{ marginTop: 5, marginBottom: 16 }}
            >
              {features.find((f) => f.scene === scene)?.title} · Applies immediately after selection
            </Label>
            <ScrollView style={{ maxHeight: 420 }}>
              {data.groups.some((g) => compatible(scene, g, data.models)) ? (
                data.groups.filter((g) => compatible(scene, g, data.models)).map((g) => (
                  <Pressable key={g.id} accessibilityRole="button" onPress={() => {
                    const target = scene; setScene(null);
                    if (target) void mutate("bindSystemAi", { scene: target, group: g.id });
                  }} style={[styles.sheetGroup, { borderBottomColor: palette.line }]}>
                    <View style={{ flexDirection: "row", alignItems: "center" }}>
                      <Label color={palette.ink} medium style={{ flex: 1 }}>{groupName(g)}</Label>
                      {findGroup(data, scene || "")?.id === g.id ? <Label color={colors.brand} size={22}>✓</Label> : null}
                    </View>
                    <View style={styles.sheetChips}>
                      {g.members?.length ? g.members.map((m, i) => (
                        <React.Fragment key={`${m}-${i}`}>
                          {i > 0 ? <Label color={palette.sub} size={14}>→</Label> : null}
                          <View style={[styles.sheetChip, { backgroundColor: palette.soft }]}>
                            <Label color={palette.sub} size={12}>{modelName(m, data.models)}</Label>
                          </View>
                        </React.Fragment>
                      )) : <Label color={palette.sub} size={13}>No models</Label>}
                    </View>
                  </Pressable>
                ))
              ) : (
                <View style={styles.empty}>
                  <Label color={palette.ink} medium>
                    No available model groups
                  </Label>
                  <Label color={palette.sub} size={13}>
                    Configure a model group supporting this feature in Magpie.
                  </Label>
                </View>
              )}
            </ScrollView>
            <Pressable accessibilityRole="button" style={styles.managementLink} onPress={() => { setScene(null); void openManagement(); }}>
              <Label color={palette.ink} medium>Manage models and providers  ›</Label>
            </Pressable>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: "center", alignItems: "center" },
  header: {
    minHeight: 64,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    gap: 8,
  },
  back: {
    width: 40,
    height: 48,
    justifyContent: "center",
    alignItems: "center",
  },
  content: { paddingHorizontal: 20, paddingBottom: 36 },
  card: { borderRadius: 16, overflow: "hidden" },
  switchRow: {
    minHeight: 64,
    paddingHorizontal: 16,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
  },
  section: { marginTop: 24, marginBottom: 12 },
  row: {
    minHeight: 64,
    paddingHorizontal: 16,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  icon: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  badge: { borderRadius: 12, paddingHorizontal: 8, paddingVertical: 4 },
  sheetGroup: { paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth },
  sheetChips: { flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: 6, marginTop: 8 },
  sheetChip: { borderRadius: 8, paddingHorizontal: 8, paddingVertical: 4 },
  managementLink: { paddingVertical: 16, alignItems: "center" },
  manage: { marginTop: 24 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  empty: { padding: 24, gap: 8 },
  modal: { flex: 1, justifyContent: "flex-end" },
  sheet: {
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    padding: 20,
    paddingBottom: 36,
  },
  grabber: {
    width: 36,
    height: 4,
    borderRadius: 4,
    alignSelf: "center",
    marginBottom: 20,
  },
  retry: { padding: 18 },
  undo: {
    position: "absolute",
    left: 20,
    right: 20,
    bottom: 24,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    flexDirection: "row",
    alignItems: "center",
  },
});
