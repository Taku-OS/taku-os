(() => {
  var __create = Object.create;
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __getProtoOf = Object.getPrototypeOf;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __require = /* @__PURE__ */ ((x) => typeof require !== "undefined" ? require : typeof Proxy !== "undefined" ? new Proxy(x, {
    get: (a, b) => (typeof require !== "undefined" ? require : a)[b]
  }) : x)(function(x) {
    if (typeof require !== "undefined") return require.apply(this, arguments);
    throw Error('Dynamic require of "' + x + '" is not supported');
  });
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
    // If the importer is in node compatibility mode or this is not an ESM
    // file that has been converted to a CommonJS file using a Babel-
    // compatible transform (i.e. "__esModule" has not been set), then set
    // "default" to the CommonJS "module.exports" for node compatibility.
    isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
    mod
  ));

  // apps/scenario_score/main.jsx
  var import_react3 = __toESM(__require("react"));
  var import_client = __require("react-dom/client");

  // apps/scenario_score/App.jsx
  var import_react2 = __toESM(__require("react"));

  // apps/scenario_score/ui.jsx
  var import_react = __toESM(__require("react"));
  function Button({ children, onClick, secondary = false, ...props }) {
    return /* @__PURE__ */ import_react.default.createElement("button", { onClick, style: { border: 0, borderRadius: 14, padding: "13px 18px", background: secondary ? "#e4ece8" : "#006b56", color: secondary ? "#006b56" : "white", fontWeight: 600 }, ...props }, children);
  }
  function Card({ children, style = {} }) {
    return /* @__PURE__ */ import_react.default.createElement("section", { style: { background: "white", borderRadius: 22, padding: 20, ...style } }, children);
  }
  function Screen({ title, subtitle, children }) {
    return /* @__PURE__ */ import_react.default.createElement("main", { style: { maxWidth: 620, margin: "auto", padding: 24, display: "grid", gap: 20 } }, /* @__PURE__ */ import_react.default.createElement("header", null, /* @__PURE__ */ import_react.default.createElement("h1", { style: { fontSize: 30, margin: "10px 0" } }, title), subtitle && /* @__PURE__ */ import_react.default.createElement("p", { style: { color: "#647872", lineHeight: 1.6 } }, subtitle)), children);
  }
  function Field({ label, value, onChange, type = "text", placeholder }) {
    return /* @__PURE__ */ import_react.default.createElement("label", { style: { display: "grid", gap: 10, margin: "12px 0", flex: 1 } }, /* @__PURE__ */ import_react.default.createElement("span", { style: { fontSize: 14, color: "#536c63" } }, label), /* @__PURE__ */ import_react.default.createElement("input", { type, value, onChange, placeholder, style: { width: "100%", padding: 14, border: "1px solid #cbd7cf", borderRadius: 12, background: "#fafcf8" } }));
  }
  var phone = {
    load(fallback) {
      try {
        return JSON.parse(Phone.read());
      } catch {
        return fallback;
      }
    },
    save(data) {
      Phone.save(JSON.stringify(data));
    },
    notify(title, text) {
      Phone.notify(title, text);
    }
  };
  phone.call = async function(method, params = {}, options = {}) {
    const response = JSON.parse(Phone.callJson(JSON.stringify({ method, params, ...options })));
    if (!response.ok) {
      const error = new Error(response.error?.message || "\u5E94\u7528\u63A5\u53E3\u8C03\u7528\u5931\u8D25");
      error.code = response.error?.code;
      error.details = response.error?.details;
      throw error;
    }
    return response.result;
  };
  function useAppApiState(method, params = {}, interval = 750) {
    const [state, setState] = import_react.default.useState(null), [error, setError] = import_react.default.useState(null);
    const signature = JSON.stringify(params);
    const reload = import_react.default.useCallback(async () => {
      const next = await phone.call(method, JSON.parse(signature));
      setState((old) => JSON.stringify(old) === JSON.stringify(next) ? old : next);
      setError(null);
      return next;
    }, [method, signature]);
    import_react.default.useEffect(() => {
      let active = true;
      const run = async () => {
        try {
          if (active) await reload();
        } catch (e) {
          if (active) setError(e.message);
        }
      };
      run();
      const timer = setInterval(run, interval);
      return () => {
        active = false;
        clearInterval(timer);
      };
    }, [reload, interval]);
    return { state, error, reload };
  }

  // apps/scenario_score/App.jsx
  function App() {
    const { state, error, reload } = useAppApiState("score.state");
    const [draft, setDraft] = (0, import_react2.useState)({ A: "", B: "" }), [message, setMessage] = (0, import_react2.useState)(""), [busy, setBusy] = (0, import_react2.useState)(false);
    async function call(method, params) {
      setBusy(true);
      try {
        await phone.call(method, params);
        await reload();
        setMessage("\u5DF2\u66F4\u65B0");
      } catch (e) {
        setMessage(e.message);
      } finally {
        setBusy(false);
      }
    }
    return /* @__PURE__ */ import_react2.default.createElement(Screen, { title: "\u53CC\u961F\u8BA1\u5206", subtitle: "\u540C\u4E00\u4EFD\u63A5\u53E3\u72B6\u6001 \xB7 \u652F\u6301\u52A0\u5206\u3001\u6263\u5206\u548C\u64A4\u9500" }, /* @__PURE__ */ import_react2.default.createElement("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 } }, ["A", "B"].map((team) => /* @__PURE__ */ import_react2.default.createElement(Card, { key: team, style: { padding: 16, textAlign: "center", borderTop: `6px solid ${team === "A" ? "#006b56" : "#4166b5"}` } }, /* @__PURE__ */ import_react2.default.createElement("h2", null, team, " \u961F"), /* @__PURE__ */ import_react2.default.createElement("div", { style: { fontSize: 64, fontWeight: 800, margin: "16px 0" } }, state?.scores[team] ?? "\u2014"), /* @__PURE__ */ import_react2.default.createElement("div", { style: { display: "flex", gap: 8, justifyContent: "center" } }, /* @__PURE__ */ import_react2.default.createElement(Button, { disabled: busy, onClick: () => call("score.add", { team, delta: 1 }) }, "+1"), /* @__PURE__ */ import_react2.default.createElement(Button, { secondary: true, disabled: busy, onClick: () => call("score.add", { team, delta: -1 }) }, "\u22121")), /* @__PURE__ */ import_react2.default.createElement(Field, { label: "\u81EA\u5B9A\u4E49\u6574\u6570\uFF08\u53EF\u8D1F\u6570\uFF09", value: draft[team], onChange: (e) => setDraft({ ...draft, [team]: e.target.value }), placeholder: "\u4F8B\u5982 +3 \u6216 -2" }), /* @__PURE__ */ import_react2.default.createElement(Button, { disabled: busy, onClick: () => call("score.add", { team, delta: /^[+-]?\d+$/.test(draft[team]) ? Number(draft[team]) : draft[team] }) }, "\u5E94\u7528\u52A0\u5206")))), /* @__PURE__ */ import_react2.default.createElement(Button, { secondary: true, disabled: busy || !state?.history.length, onClick: () => call("score.undo", {}) }, "\u64A4\u9500\u6700\u540E\u4E00\u6B65"), /* @__PURE__ */ import_react2.default.createElement("div", { role: "status", style: { color: error ? "#a32929" : "#536c63" } }, error || message || "\u7B49\u5F85\u64CD\u4F5C"), /* @__PURE__ */ import_react2.default.createElement(Card, null, /* @__PURE__ */ import_react2.default.createElement("h2", null, "\u5386\u53F2\u8BB0\u5F55"), !state?.history.length ? /* @__PURE__ */ import_react2.default.createElement("p", null, "\u6682\u65E0\u8BB0\u5F55") : /* @__PURE__ */ import_react2.default.createElement("ol", { style: { paddingLeft: 24 } }, [...state.history].reverse().map((h) => /* @__PURE__ */ import_react2.default.createElement("li", { key: h.id, style: { padding: "10px 0" } }, h.team, " \u961F\u3000", h.delta > 0 ? "+" : "", h.delta, /* @__PURE__ */ import_react2.default.createElement("small", { style: { display: "block", color: "#647872" } }, h.at))))));
  }

  // apps/scenario_score/main.jsx
  (0, import_client.createRoot)(document.getElementById("root")).render(/* @__PURE__ */ import_react3.default.createElement(App, null));
})();
