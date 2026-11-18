import Java from "frida-java-bridge";
globalThis.Java = Java;
rpc.exports = {
  run(source) {
    try {
      (0, eval)(source);
      return { ok: true };
    } catch (error) {
      send({ type: "error", description: String(error), stack: error && error.stack });
      return { ok: false, error: String(error) };
    }
  }
};
